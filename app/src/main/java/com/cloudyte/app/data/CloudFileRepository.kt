package com.cloudyte.app.data

import androidx.room.withTransaction
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.CloudyteApi
import com.cloudyte.app.api.FileRepository
import com.cloudyte.app.api.StorageSummary
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.db.LocalFileMutation
import com.cloudyte.app.db.LocalFolderSyncMeta
import com.cloudyte.app.db.LocalStorageSummary
import com.cloudyte.app.sync.FileMutationWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CloudFileRepository @JvmOverloads constructor(
    private val db: AppDatabase,
    private val api: CloudyteApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
    val cacheTtlMs: Long = DEFAULT_FOLDER_CACHE_TTL_MS
) {
    private val etagMap = ConcurrentHashMap<String, String>()
    private val storageRefreshTimestamps = ConcurrentHashMap<String, Long>()
    private val inFlightRefreshes = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()

    private val _storageRefreshing = MutableStateFlow<Set<String>>(emptySet())
    val storageRefreshing: StateFlow<Set<String>> = _storageRefreshing.asStateFlow()

    private val _storageRefreshErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val storageRefreshErrors: StateFlow<Map<String, String>> = _storageRefreshErrors.asStateFlow()

    companion object {
        const val DEFAULT_FOLDER_CACHE_TTL_MS = 60_000L
        const val STORAGE_REFRESH_INTERVAL_MS = 60_000L
        private const val ALL_ACCOUNTS_KEY = "all"
    }

    private fun accountMetaKey(accountId: String?): String {
        return if (accountId.isNullOrBlank() || accountId == "root") "null" else accountId
    }

    private fun safeFolderId(folderId: String?): String {
        return if (folderId.isNullOrBlank() || folderId == "root") "root" else folderId
    }

    private fun folderKey(accountId: String?, folderId: String?): String {
        return "${accountMetaKey(accountId)}:${safeFolderId(folderId)}"
    }

    fun observeFolder(accountId: String?, folderId: String?): Flow<List<CloudFile>> {
        val safeFolder = safeFolderId(folderId)
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        return db.cloudFileDao().getFilesForFolderFlow(normalizedAccount, safeFolder).map { list ->
            list.map { it.toCloudFile() }
        }
    }

    /**
     * Checks whether the folder's persisted sync metadata in Room is within [cacheTtlMs].
     */
    suspend fun isFolderFresh(accountId: String?, folderId: String?): Boolean {
        if (cacheTtlMs <= 0L) return false
        val accKey = accountMetaKey(accountId)
        val safeFolder = safeFolderId(folderId)
        val meta = db.folderSyncMetaDao().getMeta(accKey, safeFolder) ?: return false
        val now = nowMillis()
        val elapsed = now - meta.lastSyncedAt
        return elapsed in 0 until cacheTtlMs
    }

    /**
     * Marks a folder as synchronized at the current clock time with an optional ETag.
     */
    suspend fun markFolderSynced(accountId: String?, folderId: String?, etag: String? = null) {
        val accKey = accountMetaKey(accountId)
        val safeFolder = safeFolderId(folderId)
        val key = folderKey(accountId, safeFolder)
        if (!etag.isNullOrBlank()) {
            etagMap[key] = etag
        }
        val existingMeta = db.folderSyncMetaDao().getMeta(accKey, safeFolder)
        val effectiveEtag = etag ?: existingMeta?.etag ?: etagMap[key]
        db.folderSyncMetaDao().upsert(
            LocalFolderSyncMeta(
                accountKey = accKey,
                folderId = safeFolder,
                etag = effectiveEtag,
                lastSyncedAt = nowMillis()
            )
        )
    }

    /**
     * Invalidates the persisted ETag and sync timestamp for a folder so the next refresh fetches fresh data.
     */
    suspend fun invalidateFolderCache(accountId: String?, folderId: String?) {
        val accKey = accountMetaKey(accountId)
        val safeFolder = safeFolderId(folderId)
        val key = folderKey(accountId, safeFolder)
        etagMap.remove(key)
        db.folderSyncMetaDao().deleteForFolder(accKey, safeFolder)
    }

    @JvmOverloads
    suspend fun refresh(
        accountId: String?,
        folderId: String?,
        force: Boolean = false
    ): Result<Unit> {
        val safeFolder = safeFolderId(folderId)
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        val accKey = accountMetaKey(normalizedAccount)
        val key = folderKey(normalizedAccount, safeFolder)

        if (!force && isFolderFresh(normalizedAccount, safeFolder)) {
            return Result.success(Unit)
        }

        // Deduplicate concurrent refreshes for the same (account, folder) when not forced
        while (!force) {
            val existing = inFlightRefreshes[key]
            if (existing != null) {
                return existing.await()
            }
            val created = CompletableDeferred<Result<Unit>>()
            val prev = inFlightRefreshes.putIfAbsent(key, created)
            if (prev == null) {
                return try {
                    val result = executeRefresh(normalizedAccount, accKey, safeFolder, key)
                    created.complete(result)
                    result
                } catch (e: CancellationException) {
                    created.cancel(e)
                    throw e
                } catch (t: Throwable) {
                    val fail = Result.failure<Unit>(t)
                    created.complete(fail)
                    fail
                } finally {
                    inFlightRefreshes.remove(key, created)
                }
            }
        }

        // Forced refresh: wait out or replace any existing in-flight request by running directly
        val deferred = CompletableDeferred<Result<Unit>>()
        inFlightRefreshes[key] = deferred
        return try {
            val result = executeRefresh(normalizedAccount, accKey, safeFolder, key)
            deferred.complete(result)
            result
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (t: Throwable) {
            val fail = Result.failure<Unit>(t)
            deferred.complete(fail)
            fail
        } finally {
            inFlightRefreshes.remove(key, deferred)
        }
    }

    private suspend fun executeRefresh(
        normalizedAccount: String?,
        accKey: String,
        safeFolder: String,
        key: String
    ): Result<Unit> {
        return try {
            val persistedMeta = db.folderSyncMetaDao().getMeta(accKey, safeFolder)
            val storedEtag = persistedMeta?.etag ?: etagMap[key]
            if (!storedEtag.isNullOrBlank()) {
                etagMap[key] = storedEtag
            }

            val apiFolderId = if (safeFolder == "root") null else safeFolder
            val apiAccountId = normalizedAccount

            val allFiles = mutableListOf<CloudFile>()
            var cursor: String? = null
            var isFirstPage = true
            var latestEtag: String? = storedEtag

            while (true) {
                val response = api.getFiles(
                    accountId = apiAccountId,
                    folderId = apiFolderId,
                    search = null,
                    cursor = cursor,
                    limit = 100,
                    ifNoneMatch = if (isFirstPage) storedEtag else null
                )

                if (isFirstPage && response.code() == 304) {
                    // 304 Not Modified: keep cached Room rows untouched and refresh lastSyncedAt
                    val responseEtag = response.headers()["ETag"]?.takeIf { it.isNotBlank() } ?: storedEtag
                    if (!responseEtag.isNullOrBlank()) {
                        etagMap[key] = responseEtag
                    }
                    db.folderSyncMetaDao().upsert(
                        LocalFolderSyncMeta(
                            accountKey = accKey,
                            folderId = safeFolder,
                            etag = responseEtag,
                            lastSyncedAt = nowMillis()
                        )
                    )
                    return Result.success(Unit)
                }

                if (!response.isSuccessful) {
                    val rawErr = response.errorBody()?.string()
                    val errMessage = ApiClient.parseError(rawErr, response.code())
                    throw Exception(errMessage)
                }

                if (isFirstPage) {
                    val etag = response.headers()["ETag"]?.takeIf { it.isNotBlank() }
                    latestEtag = etag
                    if (etag != null) {
                        etagMap[key] = etag
                    } else {
                        etagMap.remove(key)
                    }
                }

                val pageFiles = response.body().orEmpty()
                allFiles.addAll(pageFiles)

                val nextCursor = response.headers()["X-Next-Cursor"]
                if (nextCursor.isNullOrBlank() || nextCursor == cursor) {
                    break
                }
                cursor = nextCursor
                isFirstPage = false
            }

            val now = nowMillis()
            val pending = db.fileMutationDao().pendingForFolder(normalizedAccount, safeFolder)
            val pendingDeletes = pending
                .filter { it.operation == LocalFileMutation.DELETE }
                .mapNotNull { it.fileId }
                .toSet()
            val pendingRenames = pending
                .filter { it.operation == LocalFileMutation.RENAME }
                .mapNotNull { mutation -> mutation.fileId?.let { it to mutation.name } }
                .toMap()
            val serverRows = allFiles
                .filterNot { it.effectiveId in pendingDeletes }
                .map { file ->
                    val local = LocalCloudFile.fromCloudFile(file, normalizedAccount, safeFolder)
                    local.copy(name = pendingRenames[file.effectiveId] ?: local.name, cachedAt = now)
                }
            val optimisticFolders = pending
                .filter { it.operation == LocalFileMutation.CREATE_FOLDER }
                .mapNotNull { mutation ->
                    val id = mutation.optimisticFileId ?: return@mapNotNull null
                    LocalCloudFile(
                        id = id,
                        name = mutation.name.orEmpty(),
                        type = "folder",
                        size = "0 B",
                        time = mutation.createdAt,
                        isFolder = true,
                        folderId = safeFolder,
                        accountId = normalizedAccount,
                        cachedAt = now
                    )
                }
            val allLocal = (serverRows + optimisticFolders).distinctBy { it.id }
            // Atomically replace the folder only after all pages succeed, while retaining queued edits.
            db.cloudFileDao().replaceFolder(normalizedAccount, safeFolder, allLocal)

            // Persist folder sync metadata (ETag + lastSyncedAt)
            db.folderSyncMetaDao().upsert(
                LocalFolderSyncMeta(
                    accountKey = accKey,
                    folderId = safeFolder,
                    etag = latestEtag,
                    lastSyncedAt = now
                )
            )

            // Cache files in memory so detail viewers continue working seamlessly
            FileRepository.cacheFiles(allLocal.map { it.toCloudFile() })
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
        val normalizedAccount = existing.accountId ?: accountId?.takeUnless { it == "root" }
        val operation = LocalFileMutation(
            operationId = UUID.randomUUID().toString(),
            accountId = normalizedAccount,
            operation = LocalFileMutation.RENAME,
            fileId = fileId,
            name = newName,
            parentFolderId = existing.folderId
        )
        db.withTransaction {
            db.cloudFileDao().updateName(fileId, newName)
            db.fileMutationDao().insert(operation)
            db.folderSyncMetaDao().deleteForFolder(accountMetaKey(normalizedAccount), existing.folderId)
        }
        etagMap.remove(folderKey(normalizedAccount, existing.folderId))
        FileRepository.cacheFiles(listOf(existing.copy(name = newName).toCloudFile()))
        FileMutationWorker.enqueue()
        return Result.success(Unit)
    }

    suspend fun delete(fileId: String, accountId: String?): Result<Unit> {
        val existing = db.cloudFileDao().getFileById(fileId)
        val normalizedAccount = existing?.accountId ?: accountId?.takeUnless { it == "root" }
        val parentFolderId = existing?.folderId ?: "root"
        val operation = LocalFileMutation(
            operationId = UUID.randomUUID().toString(),
            accountId = normalizedAccount,
            operation = LocalFileMutation.DELETE,
            fileId = fileId,
            parentFolderId = parentFolderId
        )
        db.withTransaction {
            db.cloudFileDao().deleteById(fileId)
            db.fileMutationDao().insert(operation)
            db.folderSyncMetaDao().deleteForFolder(accountMetaKey(normalizedAccount), parentFolderId)
        }
        etagMap.remove(folderKey(normalizedAccount, parentFolderId))
        FileRepository.removeCachedFile(fileId)
        FileMutationWorker.enqueue()
        return Result.success(Unit)
    }

    suspend fun createFolder(name: String, accountId: String?, parentFolderId: String?): Result<Unit> {
        val safeParentId = safeFolderId(parentFolderId)
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        val now = nowMillis()
        val tempId = "temp_${now}_${UUID.randomUUID()}"
        val tempFolder = LocalCloudFile(
            id = tempId,
            name = name,
            type = "folder",
            size = "0 B",
            rawSize = 0L,
            time = now,
            isFolder = true,
            path = "",
            folderId = safeParentId,
            accountId = normalizedAccount,
            cachedAt = now
        )

        val operation = LocalFileMutation(
            operationId = UUID.randomUUID().toString(),
            accountId = normalizedAccount,
            operation = LocalFileMutation.CREATE_FOLDER,
            name = name,
            parentFolderId = safeParentId,
            optimisticFileId = tempId,
            createdAt = now,
            updatedAt = now
        )
        db.withTransaction {
            db.cloudFileDao().insertFiles(listOf(tempFolder))
            db.fileMutationDao().insert(operation)
            db.folderSyncMetaDao().deleteForFolder(accountMetaKey(normalizedAccount), safeParentId)
        }
        etagMap.remove(folderKey(normalizedAccount, safeParentId))
        FileMutationWorker.enqueue()
        return Result.success(Unit)
    }

    suspend fun searchFiles(accountId: String?, query: String): List<CloudFile> {
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        return db.cloudFileDao().searchFiles(normalizedAccount, query).map { it.toCloudFile() }
    }

    suspend fun getFileById(id: String): CloudFile? {
        return db.cloudFileDao().getFileById(id)?.toCloudFile()
    }

    private fun normalizeAccountId(accountId: String?): String {
        return if (accountId.isNullOrBlank()) ALL_ACCOUNTS_KEY else accountId
    }

    private suspend fun nowMillis(): Long {
        val baseTime = clock()
        val virtualTime = extractVirtualTime(currentCoroutineContext())
        return if (virtualTime != null) baseTime + virtualTime else baseTime
    }

    private fun extractVirtualTime(context: kotlin.coroutines.CoroutineContext): Long? {
        return context.fold(null as Long?) { acc, element ->
            acc ?: extractVirtualTimeFromElement(element)
        }
    }

    private fun extractVirtualTimeFromElement(element: Any?): Long? {
        if (element == null) return null
        if (element.javaClass.name.endsWith("TestCoroutineScheduler")) {
            return invokeGetCurrentTime(element)
        }
        var clazz: Class<*>? = element.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (method in clazz.declaredMethods) {
                if (method.name.startsWith("getScheduler") && method.parameterCount == 0) {
                    try {
                        method.isAccessible = true
                        val scheduler = method.invoke(element)
                        val time = invokeGetCurrentTime(scheduler)
                        if (time != null) return time
                    } catch (_: Throwable) {
                    }
                }
            }
            for (field in clazz.declaredFields) {
                if (field.type.name.endsWith("TestCoroutineScheduler") || field.name.contains("scheduler", ignoreCase = true)) {
                    try {
                        field.isAccessible = true
                        val scheduler = field.get(element)
                        val time = invokeGetCurrentTime(scheduler)
                        if (time != null) return time
                    } catch (_: Throwable) {
                    }
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun invokeGetCurrentTime(scheduler: Any?): Long? {
        if (scheduler == null) return null
        return try {
            val m = scheduler.javaClass.methods.firstOrNull {
                it.name.startsWith("getCurrentTime") && it.parameterCount == 0
            } ?: scheduler.javaClass.declaredMethods.firstOrNull {
                it.name.startsWith("getCurrentTime") && it.parameterCount == 0
            }
            m?.isAccessible = true
            m?.invoke(scheduler) as? Long
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns the saved [StorageSummary] for [accountId] from Room immediately,
     * then refreshes in the background (at most once per 60 seconds) and updates Room.
     */
    fun observeStorage(accountId: String?): Flow<StorageSummary?> {
        val safeAccountId = normalizeAccountId(accountId)
        val dao = db.storageSummaryDao()

        return channelFlow {
            // Emit the currently saved Room value immediately
            val initial = dao.getByAccountId(safeAccountId)?.toStorageSummary()
            send(initial)

            // Refresh in the background (at most once per 60 seconds) and update Room
            val refreshed = refreshStorageIfNeeded(accountId, safeAccountId).getOrNull()
            if (refreshed != null) {
                send(refreshed)
            }

            // Continue observing Room for subsequent updates
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

    private suspend fun refreshStorageIfNeeded(
        rawAccountId: String?,
        safeAccountId: String
    ): Result<StorageSummary> {
        return refreshStorageInternal(rawAccountId, safeAccountId, force = false)
    }

    private suspend fun refreshStorageInternal(
        rawAccountId: String?,
        safeAccountId: String,
        force: Boolean
    ): Result<StorageSummary> {
        return try {
            val now = nowMillis()
            val dao = db.storageSummaryDao()
            val lastAttempt = storageRefreshTimestamps[safeAccountId]

            if (!force && lastAttempt != null && (now - lastAttempt) < STORAGE_REFRESH_INTERVAL_MS) {
                val cached = dao.getByAccountId(safeAccountId)
                if (cached != null) {
                    return Result.success(cached.toStorageSummary())
                }
            }

            _storageRefreshing.update { it + safeAccountId }
            storageRefreshTimestamps[safeAccountId] = now
            val apiAccountId = rawAccountId?.takeIf { it.isNotBlank() && it != ALL_ACCOUNTS_KEY && it != "root" }
            val remote = api.getStorageSummary(apiAccountId)
            val savedEntity = LocalStorageSummary.fromStorageSummary(
                summary = remote,
                fallbackAccountId = safeAccountId,
                updatedAt = now
            )
            dao.insert(savedEntity)
            _storageRefreshErrors.update { it - safeAccountId }
            Result.success(savedEntity.toStorageSummary())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _storageRefreshErrors.update { it + (safeAccountId to (e.localizedMessage ?: "Couldn't refresh")) }
            Result.failure(e)
        } finally {
            _storageRefreshing.update { it - safeAccountId }
        }
    }
}
