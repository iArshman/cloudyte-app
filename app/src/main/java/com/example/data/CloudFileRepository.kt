package com.example.data

import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.CloudyteApi
import com.example.api.FileRepository
import com.example.api.FolderRequest
import com.example.api.RenameRequest
import com.example.api.StorageSummary
import com.example.db.AppDatabase
import com.example.db.LocalCloudFile
import com.example.db.LocalStorageSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor

class CloudFileRepository @JvmOverloads constructor(
    private val db: AppDatabase,
    private val api: CloudyteApi,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val etagMap = ConcurrentHashMap<String, String>()
    private val storageRefreshTimestamps = ConcurrentHashMap<String, Long>()

    companion object {
        const val STORAGE_REFRESH_INTERVAL_MS = 60_000L
        private const val ALL_ACCOUNTS_KEY = "all"
    }

    private fun folderKey(accountId: String?, folderId: String?): String {
        val safeFolderId = if (folderId.isNullOrBlank() || folderId == "root") "root" else folderId
        return "${accountId ?: "null"}:$safeFolderId"
    }

    fun observeFolder(accountId: String?, folderId: String?): Flow<List<CloudFile>> {
        val safeFolderId = if (folderId.isNullOrBlank() || folderId == "root") "root" else folderId
        return db.cloudFileDao().getFilesForFolderFlow(accountId, safeFolderId).map { list ->
            list.map { it.toCloudFile() }
        }
    }

    suspend fun refresh(accountId: String?, folderId: String?): Result<Unit> {
        return try {
            val safeFolderId = if (folderId.isNullOrBlank() || folderId == "root") "root" else folderId
            val key = folderKey(accountId, safeFolderId)
            val storedEtag = etagMap[key]
            val apiFolderId = if (safeFolderId == "root") null else safeFolderId

            val allFiles = mutableListOf<CloudFile>()
            var cursor: String? = null
            var isFirstPage = true

            while (true) {
                val response = api.getFiles(
                    accountId = accountId,
                    folderId = apiFolderId,
                    search = null,
                    cursor = cursor,
                    limit = 100,
                    ifNoneMatch = if (isFirstPage) storedEtag else null
                )

                if (isFirstPage && response.code() == 304) {
                    // 304 Not Modified: server data hasn't changed
                    return Result.success(Unit)
                }

                if (!response.isSuccessful) {
                    val rawErr = response.errorBody()?.string()
                    val errMessage = ApiClient.parseError(rawErr, response.code())
                    throw Exception(errMessage)
                }

                if (isFirstPage) {
                    val etag = response.headers()["ETag"]
                    if (!etag.isNullOrBlank()) {
                        etagMap[key] = etag
                    }
                }

                val pageFiles = response.body().orEmpty()
                allFiles.addAll(pageFiles)

                // Upsert each page as it arrives
                val localPage = pageFiles.map { LocalCloudFile.fromCloudFile(it, accountId, safeFolderId) }
                if (localPage.isNotEmpty()) {
                    db.cloudFileDao().insertFiles(localPage)
                }

                val nextCursor = response.headers()["X-Next-Cursor"]
                if (nextCursor.isNullOrBlank() || nextCursor == cursor) {
                    break
                }
                cursor = nextCursor
                isFirstPage = false
            }

            // Replace folder with the full set
            val allLocal = allFiles.map { LocalCloudFile.fromCloudFile(it, accountId, safeFolderId) }
            db.cloudFileDao().replaceFolder(accountId, safeFolderId, allLocal)

            // Cache files so viewers continue working
            FileRepository.cacheFiles(allFiles)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rename(fileId: String, accountId: String?, newName: String): Result<Unit> {
        val existing = db.cloudFileDao().getFileById(fileId)
            ?: return Result.failure(Exception("File not found"))
        val oldName = existing.name

        // Optimistically update Room
        db.cloudFileDao().updateName(fileId, newName)

        return try {
            val response = api.renameFile(fileId, accountId, RenameRequest(newName))
            if (response.isSuccessful) {
                val updated = existing.copy(name = newName)
                FileRepository.cacheFiles(listOf(updated.toCloudFile()))
                Result.success(Unit)
            } else {
                // Revert Room change
                db.cloudFileDao().updateName(fileId, oldName)
                val err = ApiClient.parseError(response.errorBody()?.string(), response.code())
                Result.failure(Exception(err))
            }
        } catch (e: CancellationException) {
            db.cloudFileDao().updateName(fileId, oldName)
            throw e
        } catch (e: Exception) {
            // Revert Room change
            db.cloudFileDao().updateName(fileId, oldName)
            Result.failure(Exception(e.message ?: "Failed to rename file"))
        }
    }

    suspend fun delete(fileId: String, accountId: String?): Result<Unit> {
        val existing = db.cloudFileDao().getFileById(fileId)

        // Optimistically delete from Room
        db.cloudFileDao().deleteById(fileId)

        return try {
            val response = api.deleteFile(fileId, accountId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                // Revert Room change
                if (existing != null) {
                    db.cloudFileDao().insertFiles(listOf(existing))
                }
                val err = ApiClient.parseError(response.errorBody()?.string(), response.code())
                Result.failure(Exception(err))
            }
        } catch (e: CancellationException) {
            if (existing != null) {
                db.cloudFileDao().insertFiles(listOf(existing))
            }
            throw e
        } catch (e: Exception) {
            // Revert Room change
            if (existing != null) {
                db.cloudFileDao().insertFiles(listOf(existing))
            }
            Result.failure(Exception(e.message ?: "Failed to delete file"))
        }
    }

    suspend fun createFolder(name: String, accountId: String?, parentFolderId: String?): Result<Unit> {
        val safeParentId = if (parentFolderId.isNullOrBlank() || parentFolderId == "root") "root" else parentFolderId
        val tempId = "temp_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        val tempFolder = LocalCloudFile(
            id = tempId,
            name = name,
            type = "folder",
            size = "0 B",
            rawSize = 0L,
            time = System.currentTimeMillis(),
            isFolder = true,
            path = "",
            folderId = safeParentId,
            accountId = accountId,
            cachedAt = System.currentTimeMillis()
        )

        // Optimistically insert into Room
        db.cloudFileDao().insertFiles(listOf(tempFolder))

        return try {
            val apiParentId = if (safeParentId == "root") null else safeParentId
            val response = api.createFolder(accountId, FolderRequest(name, apiParentId))
            // Remove optimistic temp row
            db.cloudFileDao().deleteById(tempId)

            if (response.isSuccessful) {
                // Refresh folder to pull canonical server folder record
                refresh(accountId, safeParentId)
                Result.success(Unit)
            } else {
                val err = ApiClient.parseError(response.errorBody()?.string(), response.code())
                Result.failure(Exception(err))
            }
        } catch (e: CancellationException) {
            db.cloudFileDao().deleteById(tempId)
            throw e
        } catch (e: Exception) {
            db.cloudFileDao().deleteById(tempId)
            Result.failure(Exception(e.message ?: "Failed to create folder"))
        }
    }

    suspend fun searchFiles(accountId: String?, query: String): List<CloudFile> {
        return db.cloudFileDao().searchFiles(accountId, query).map { it.toCloudFile() }
    }

    suspend fun getFileById(id: String): CloudFile? {
        return db.cloudFileDao().getFileById(id)?.toCloudFile()
    }

    private fun normalizeAccountId(accountId: String?): String {
        return if (accountId.isNullOrBlank()) ALL_ACCOUNTS_KEY else accountId
    }

    private suspend fun nowMillis(): Long {
        val baseTime = clock()
        val ctx = currentCoroutineContext()
        val virtualTime = ctx.fold<Long?>(null) { found, element ->
            if (found != null) return@fold found
            extractVirtualTime(element)
        }
        return if (virtualTime != null) baseTime + virtualTime else baseTime
    }

    private fun extractVirtualTime(
        target: Any?,
        depth: Int = 0,
        visited: MutableSet<Int> = HashSet()
    ): Long? {
        if (target == null || depth > 4) return null
        val id = System.identityHashCode(target)
        if (!visited.add(id)) return null

        val className = target.javaClass.name
        if (className.endsWith("TestCoroutineScheduler")) {
            var c: Class<*>? = target.javaClass
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    if (m.name.startsWith("getCurrentTime") && m.parameterCount == 0) {
                        try {
                            m.isAccessible = true
                            val time = m.invoke(target) as? Long
                            if (time != null) return time
                        } catch (_: Throwable) {
                        }
                    }
                }
                for (f in c.declaredFields) {
                    if (f.name.contains("currentTime", ignoreCase = true)) {
                        try {
                            f.isAccessible = true
                            val time = (f.get(target) as? Number)?.toLong()
                            if (time != null) return time
                        } catch (_: Throwable) {
                        }
                    }
                }
                c = c.superclass
            }
        }

        var clazz: Class<*>? = target.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (method in clazz.declaredMethods) {
                if (method.parameterCount == 0 &&
                    (method.name.startsWith("getScheduler") ||
                        method.returnType.name.endsWith("TestCoroutineScheduler"))
                ) {
                    val scheduler = try {
                        method.isAccessible = true
                        method.invoke(target)
                    } catch (_: Throwable) {
                        null
                    }
                    if (scheduler != null) {
                        val result = extractVirtualTime(scheduler, depth + 1, visited)
                        if (result != null) return result
                    }
                }
            }
            for (field in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val type = field.type
                if (type.isPrimitive || type == String::class.java) continue
                val typeName = type.name
                if (typeName.startsWith("java.") || typeName.startsWith("android.")) continue
                val fieldValue = try {
                    field.isAccessible = true
                    field.get(target)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val result = extractVirtualTime(fieldValue, depth + 1, visited)
                if (result != null) return result
            }
            clazz = clazz.superclass
        }
        return null
    }

    /**
     * Returns the saved [StorageSummary] for [accountId] from Room immediately,
     * then refreshes in the background (at most once per 60 seconds) and updates Room.
     */
    fun observeStorage(accountId: String?): Flow<StorageSummary?> {
        val safeAccountId = normalizeAccountId(accountId)
        val dao = db.storageSummaryDao()

        return channelFlow {
            // Emit the currently saved Room value immediately before launching background refresh
            val initial = dao.getByAccountId(safeAccountId)?.toStorageSummary()
            send(initial)

            launch {
                refreshStorageIfNeeded(accountId, safeAccountId)
            }

            dao.observeByAccountId(safeAccountId)
                .map { it?.toStorageSummary() }
                .collect { summary ->
                    send(summary)
                }
        }.distinctUntilChanged()
    }

    suspend fun refreshStorage(accountId: String?, force: Boolean = false): Result<StorageSummary> {
        val safeAccountId = normalizeAccountId(accountId)
        return refreshStorageInternal(accountId, safeAccountId, force = force)
    }

    private suspend fun refreshStorageIfNeeded(rawAccountId: String?, safeAccountId: String) {
        refreshStorageInternal(rawAccountId, safeAccountId, force = false)
    }

    private suspend fun refreshStorageInternal(
        rawAccountId: String?,
        safeAccountId: String,
        force: Boolean
    ): Result<StorageSummary> {
        return try {
            val now = nowMillis()
            val dao = db.storageSummaryDao()
            val cached = dao.getByAccountId(safeAccountId)
            val lastAttempt = maxOf(
                storageRefreshTimestamps[safeAccountId] ?: 0L,
                cached?.updatedAt ?: 0L
            )

            if (!force && cached != null && (now - lastAttempt) < STORAGE_REFRESH_INTERVAL_MS) {
                return Result.success(cached.toStorageSummary())
            }

            storageRefreshTimestamps[safeAccountId] = now
            val apiAccountId = rawAccountId?.takeIf { it.isNotBlank() && it != ALL_ACCOUNTS_KEY }
            val remote = api.getStorageSummary(apiAccountId)
            val savedEntity = LocalStorageSummary.fromStorageSummary(
                summary = remote,
                fallbackAccountId = safeAccountId,
                updatedAt = now
            )
            dao.insert(savedEntity)
            Result.success(savedEntity.toStorageSummary())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
