package com.cloudyte.app.data

import androidx.room.withTransaction
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.CloudyteApi
import com.cloudyte.app.api.FileRepository
import com.cloudyte.app.api.FolderRequest
import com.cloudyte.app.api.StorageSummary
import com.cloudyte.app.api.formatByteCount
import com.cloudyte.app.api.parseBytes
import com.cloudyte.app.core.security.ContentKeyProvider
import com.cloudyte.app.core.security.CryptoConstants
import com.cloudyte.app.core.security.EncryptedMetadataCodec
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.db.LocalFileMutation
import com.cloudyte.app.db.LocalFileTombstone
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CloudFileRepository @JvmOverloads constructor(
    private val db: AppDatabase,
    private val api: CloudyteApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
    val cacheTtlMs: Long = DEFAULT_FOLDER_CACHE_TTL_MS,
    private val contentKeyProvider: ContentKeyProvider? = null,
    private val metadataCodec: EncryptedMetadataCodec = EncryptedMetadataCodec()
) {
    private val etagMap = ConcurrentHashMap<String, String>()
    private val folderMemoryCache = ConcurrentHashMap<String, List<CloudFile>>()
    private val recentlyDeletedCache = ConcurrentHashMap<String, LocalCloudFile>()
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

    /**
     * Synchronous 0ms snapshot lookup for instant navigation without waiting for Room's async Flow.
     */
    fun getCachedFolderSnapshot(accountId: String?, folderId: String?): List<CloudFile>? {
        val key = folderKey(accountId, folderId)
        return folderMemoryCache[key]
    }

    /**
     * Pre-warms in-memory snapshots from local Room DB for child folders so navigating into
     * any already-synced subfolder happens in 0ms on the very first frame.
     */
    suspend fun warmSubfolderCachesFromDb(accountId: String?, subfolderIds: List<String>) {
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        for (subId in subfolderIds) {
            val safeSub = safeFolderId(subId)
            val key = folderKey(normalizedAccount, safeSub)
            if (!folderMemoryCache.containsKey(key)) {
                val localRows = db.cloudFileDao().getFilesForFolder(normalizedAccount, safeSub)
                if (localRows.isNotEmpty()) {
                    folderMemoryCache[key] = localRows.map { it.toCloudFile() }
                }
            }
        }
    }

    /**
     * Speculatively prefetches a subfolder over the network if not already fresh.
     */
    suspend fun prefetchSubfolder(accountId: String?, folderId: String?) {
        if (folderId.isNullOrBlank()) return
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        val safeSub = safeFolderId(folderId)
        if (!isFolderFresh(normalizedAccount, safeSub)) {
            refresh(normalizedAccount, safeSub, force = false, maxPages = 1)
        } else {
            warmSubfolderCachesFromDb(normalizedAccount, listOf(safeSub))
        }
    }

    fun observeFolder(accountId: String?, folderId: String?): Flow<List<CloudFile>> {
        val safeFolder = safeFolderId(folderId)
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        val key = folderKey(normalizedAccount, safeFolder)
        return db.cloudFileDao().getFilesForFolderFlow(normalizedAccount, safeFolder)
            .map { list -> list.map { it.toCloudFile() } }
            .onEach { mapped -> folderMemoryCache[key] = mapped }
            .onStart {
                folderMemoryCache[key]?.let { cached -> emit(cached) }
            }
            .distinctUntilChanged()
    }

    fun observeHasMorePages(accountId: String?, folderId: String?): Flow<Boolean> {
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        val accKey = accountMetaKey(normalizedAccount)
        val safeFolder = safeFolderId(folderId)
        return db.folderSyncMetaDao().observeMeta(accKey, safeFolder)
            .map { meta ->
                meta != null && !meta.isCompleteSnapshot && !meta.continuationToken.isNullOrBlank()
            }
            .distinctUntilChanged()
    }

    suspend fun loadNextPage(accountId: String?, folderId: String?): Result<Unit> {
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        return refresh(normalizedAccount, folderId, force = true, maxPages = 1)
    }

    /**
     * Checks whether the folder's persisted sync metadata in Room is within [cacheTtlMs].
     */
    suspend fun isFolderFresh(accountId: String?, folderId: String?): Boolean {
        if (cacheTtlMs <= 0L) return false
        val accKey = accountMetaKey(accountId)
        val safeFolder = safeFolderId(folderId)
        val meta = db.folderSyncMetaDao().getMeta(accKey, safeFolder) ?: return false
        if (!meta.isCompleteSnapshot || meta.syncState == "FAILED") return false
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
                lastSyncedAt = nowMillis(),
                syncState = "SUCCESS",
                lastSuccessfulAt = nowMillis(),
                isCompleteSnapshot = true
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
        force: Boolean = false,
        providerTypeHint: String? = null,
        maxPages: Int = Int.MAX_VALUE
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
                    val result = executeRefresh(normalizedAccount, accKey, safeFolder, key, providerTypeHint, maxPages)
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
            val result = executeRefresh(normalizedAccount, accKey, safeFolder, key, providerTypeHint, maxPages)
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
        key: String,
        providerTypeHint: String? = null,
        maxPages: Int = Int.MAX_VALUE
    ): Result<Unit> {
        val persistedMeta = db.folderSyncMetaDao().getMeta(accKey, safeFolder)
        val resolvedProviderType = db.accountDao().getAccountById(normalizedAccount ?: "")?.accountType
            ?.takeIf { it.isNotBlank() }
            ?: providerTypeHint?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: persistedMeta?.providerType?.takeIf { it.isNotBlank() }
            ?: "unknown"
        val resumeCursor = persistedMeta?.continuationToken?.takeIf {
            it.isNotBlank() && !persistedMeta.isCompleteSnapshot && persistedMeta.syncState == "PARTIAL"
        }
        var currentContinuationToken: String? = resumeCursor

        return try {
            val storedEtag = persistedMeta?.etag ?: etagMap[key]
            val attemptAt = nowMillis()
            db.folderSyncMetaDao().upsert(
                LocalFolderSyncMeta(
                    accountKey = accKey,
                    folderId = safeFolder,
                    etag = storedEtag,
                    lastSyncedAt = persistedMeta?.lastSyncedAt ?: 0L,
                    providerType = resolvedProviderType,
                    syncState = "RUNNING",
                    lastRequestedAt = attemptAt,
                    lastSuccessfulAt = persistedMeta?.lastSuccessfulAt,
                    lastAttemptAt = attemptAt,
                    isCompleteSnapshot = false,
                    continuationToken = currentContinuationToken,
                    remoteRevision = persistedMeta?.remoteRevision
                )
            )
            if (!storedEtag.isNullOrBlank()) {
                etagMap[key] = storedEtag
            }

            val apiFolderId = if (safeFolder == "root") null else safeFolder
            val apiAccountId = normalizedAccount

            val allFiles = mutableListOf<CloudFile>()
            val explicitDeletedIds = linkedSetOf<String>()
            var isServerDeltaResponse = false
            var cursor: String? = resumeCursor
            var isFirstPage = resumeCursor == null
            val startedFromContinuation = resumeCursor != null
            var latestEtag: String? = storedEtag
            var pagesFetched = 0
            val pageBudget = maxPages.coerceAtLeast(1)

            val pending = db.fileMutationDao().pendingForFolder(normalizedAccount, safeFolder)
            val pendingDeletes = pending
                .filter { it.operation == LocalFileMutation.DELETE }
                .mapNotNull { it.fileId }
                .toSet()
            val pendingRenames = pending
                .filter { it.operation == LocalFileMutation.RENAME }
                .mapNotNull { mutation -> mutation.fileId?.let { it to mutation.name } }
                .toMap()

            while (pagesFetched < pageBudget) {
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
                    val syncTime = nowMillis()
                    db.folderSyncMetaDao().upsert(
                        LocalFolderSyncMeta(
                            accountKey = accKey,
                            folderId = safeFolder,
                            etag = responseEtag,
                            lastSyncedAt = syncTime,
                            providerType = resolvedProviderType,
                            syncState = "SUCCESS",
                            lastRequestedAt = attemptAt,
                            lastSuccessfulAt = syncTime,
                            lastAttemptAt = attemptAt,
                            isCompleteSnapshot = true,
                            continuationToken = null,
                            remoteRevision = responseEtag
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
                    val syncModeHeader = response.headers()["X-Sync-Mode"]
                    if (syncModeHeader.equals("delta", ignoreCase = true)) {
                        isServerDeltaResponse = true
                    }
                }

                response.headers()["X-Deleted-Ids"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.let { explicitDeletedIds.addAll(it) }

                val rawPageFiles = response.body().orEmpty()
                val activePageFiles = ArrayList<CloudFile>(rawPageFiles.size)
                for (item in rawPageFiles) {
                    if (item.type.equals("deleted", ignoreCase = true)) {
                        if (item.effectiveId.isNotBlank()) {
                            explicitDeletedIds.add(item.effectiveId)
                            isServerDeltaResponse = true
                        }
                    } else {
                        activePageFiles.add(item)
                    }
                }
                allFiles.addAll(activePageFiles)
                pagesFetched++

                val nextCursor = response.headers()["X-Next-Cursor"]?.takeIf { it.isNotBlank() && it != cursor }
                currentContinuationToken = nextCursor
                if (nextCursor == null) {
                    break
                }
                cursor = nextCursor
                isFirstPage = false
            }

            val now = nowMillis()
            val serverRows = allFiles
                .filterNot { it.effectiveId in pendingDeletes }
                .map { file ->
                    resolveRemoteCloudFileToLocal(
                        file = file,
                        accountId = normalizedAccount,
                        folderId = safeFolder,
                        pendingRename = pendingRenames[file.effectiveId],
                        now = now
                    )
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
            val isCompleteListing = currentContinuationToken == null && !startedFromContinuation && !isServerDeltaResponse
            val snapshot = com.cloudyte.app.core.sync.ReconciliationPolicy.Snapshot(
                files = allLocal,
                isComplete = isCompleteListing,
                continuationToken = currentContinuationToken,
                remoteRevision = latestEtag,
                isDeltaMode = isServerDeltaResponse,
                explicitDeletedIds = explicitDeletedIds
            )

            // True Row-Level Delta Sync: only write added/modified rows and remove stale rows.
            // If 0 rows changed and 0 rows were deleted, skip SQLite table writes completely.
            val existingRows = db.cloudFileDao().getFilesForFolder(normalizedAccount, safeFolder)
            val delta = com.cloudyte.app.core.sync.ReconciliationPolicy.computeDelta(existingRows, snapshot)

            db.withTransaction {
                if (delta.staleIds.isNotEmpty()) {
                    val existingIds = existingRows.map { it.id }
                    val tombstones = com.cloudyte.app.core.sync.ReconciliationPolicy.tombstonesFor(
                        existingIds = existingIds,
                        snapshot = snapshot,
                        accountKey = accKey,
                        providerType = resolvedProviderType,
                        folderId = safeFolder,
                        deletedAt = now
                    )
                    if (tombstones.isNotEmpty()) {
                        db.fileTombstoneDao().insertAll(tombstones)
                    }
                    db.cloudFileDao().deleteByIds(delta.staleIds)
                }
                val currentIds = allLocal.map { it.id }
                if (currentIds.isNotEmpty()) {
                    db.fileTombstoneDao().removeForRemoteIds(accKey, safeFolder, currentIds)
                }
                if (delta.toUpsert.isNotEmpty()) {
                    db.cloudFileDao().insertFiles(delta.toUpsert)
                }
            }

            val isFullySynced = currentContinuationToken == null
            db.folderSyncMetaDao().upsert(
                LocalFolderSyncMeta(
                    accountKey = accKey,
                    folderId = safeFolder,
                    etag = latestEtag,
                    lastSyncedAt = if (isFullySynced) now else (persistedMeta?.lastSyncedAt ?: 0L),
                    providerType = resolvedProviderType,
                    syncState = if (isFullySynced) "SUCCESS" else "PARTIAL",
                    lastRequestedAt = attemptAt,
                    lastSuccessfulAt = if (isFullySynced) now else persistedMeta?.lastSuccessfulAt,
                    lastAttemptAt = attemptAt,
                    isCompleteSnapshot = isFullySynced,
                    continuationToken = currentContinuationToken,
                    remoteRevision = latestEtag
                )
            )

            // Cache files in memory so instant navigation and detail viewers work in 0ms
            val cloudFileSnapshot = if (isCompleteListing) {
                allLocal.map { it.toCloudFile() }
            } else {
                db.cloudFileDao().getFilesForFolder(normalizedAccount, safeFolder).map { it.toCloudFile() }
            }
            folderMemoryCache[key] = cloudFileSnapshot
            FileRepository.cacheFiles(cloudFileSnapshot)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val previous = db.folderSyncMetaDao().getMeta(accKey, safeFolder) ?: persistedMeta
            db.folderSyncMetaDao().upsert(
                LocalFolderSyncMeta(
                    accountKey = accKey,
                    folderId = safeFolder,
                    etag = previous?.etag,
                    lastSyncedAt = previous?.lastSyncedAt ?: 0L,
                    providerType = resolvedProviderType,
                    syncState = "FAILED",
                    lastRequestedAt = previous?.lastRequestedAt,
                    lastSuccessfulAt = previous?.lastSuccessfulAt,
                    lastAttemptAt = nowMillis(),
                    isCompleteSnapshot = false,
                    continuationToken = currentContinuationToken ?: previous?.continuationToken,
                    remoteRevision = previous?.remoteRevision,
                    lastErrorCode = "REFRESH_FAILED",
                    lastErrorMessage = e.message
                )
            )
            Result.failure(e)
        }
    }

    internal fun resolveRemoteCloudFileToLocal(
        file: CloudFile,
        accountId: String?,
        folderId: String?,
        pendingRename: String? = null,
        now: Long = System.currentTimeMillis()
    ): LocalCloudFile {
        val isEncryptedFile = file.isEncrypted || file.encryptedMetadata != null
        if (!isEncryptedFile) {
            val local = LocalCloudFile.fromCloudFile(file, accountId, folderId)
            return local.copy(name = pendingRename ?: local.name, cachedAt = now)
        }

        val safeFolder = safeFolderId(folderId)
        val resolvedAccId = file.effectiveAccountId.ifBlank { accountId.orEmpty() }
        val rawRemoteName = file.opaqueRemoteName
            ?: file.name
            ?: file.fileName
            ?: file.title
        val envelope = file.encryptedMetadata
        val serializedEnvelope = envelope?.let {
            runCatching { metadataCodec.serializeEnvelopeToJson(it) }.getOrNull()
        }
        val keyId = file.encryptionKeyId ?: envelope?.keyId
        val encryptedBytes = file.encryptedSizeBytes ?: parseBytes(file.size)

        if (envelope != null && contentKeyProvider != null) {
            var keyMaterial: com.cloudyte.app.core.security.ContentKeyMaterial? = null
            try {
                keyMaterial = contentKeyProvider.getContentKeyForDecryption(keyId)
                val decrypted = metadataCodec.decryptMetadata(envelope, keyMaterial)
                val resolvedName = pendingRename ?: decrypted.originalName
                val ext = decrypted.originalExtension.ifBlank {
                    resolvedName.substringAfterLast('.', "")
                }.lowercase(Locale.US)
                val resolvedType = when {
                    ext.isNotBlank() && ext.length <= 6 -> ext
                    decrypted.originalMimeType.isNotBlank() ->
                        decrypted.originalMimeType.substringAfterLast('/', "file").lowercase(Locale.US)
                    else -> "file"
                }
                val fileId = if (file.effectiveId.isNotBlank()) {
                    file.effectiveId
                } else {
                    "${resolvedAccId}_${safeFolder}_${rawRemoteName ?: resolvedName}"
                }

                return LocalCloudFile(
                    id = fileId,
                    name = resolvedName,
                    type = resolvedType,
                    size = formatByteCount(decrypted.plaintextSizeBytes),
                    rawSize = decrypted.plaintextSizeBytes,
                    time = if ((file.time ?: 0L) > 0L) file.time!! else decrypted.modifiedAt,
                    isFolder = false,
                    path = decrypted.relativePath ?: "",
                    folderId = safeFolder,
                    accountId = resolvedAccId,
                    mimeType = decrypted.originalMimeType,
                    cachedAt = now,
                    updatedAt = file.updatedAt ?: decrypted.modifiedAt,
                    thumbUrl = null,
                    isEncrypted = true,
                    encryptionFormatVersion = file.encryptionFormatVersion ?: CryptoConstants.FORMAT_VERSION_V1,
                    encryptionKeyId = keyId,
                    encryptedMetadata = serializedEnvelope,
                    encryptedSizeBytes = encryptedBytes,
                    encryptedChecksum = file.encryptedChecksum,
                    opaqueRemoteName = rawRemoteName,
                    plaintextSizeBytes = decrypted.plaintextSizeBytes,
                    originalMetadataAvailable = true
                )
            } catch (_: Exception) {
                // Fall through to safe fallback when key is unavailable or metadata is corrupted
            } finally {
                keyMaterial?.destroy()
            }
        }

        val fallbackName = pendingRename ?: if (file.originalMetadataAvailable && !file.name.isNullOrBlank()) {
            file.name
        } else {
            CryptoConstants.ENCRYPTED_FALLBACK_DISPLAY_NAME
        }
        val fallbackAvailable = file.originalMetadataAvailable && fallbackName != CryptoConstants.ENCRYPTED_FALLBACK_DISPLAY_NAME
        val fallbackSize = if (fallbackAvailable && file.plaintextSizeBytes != null) {
            file.plaintextSizeBytes
        } else {
            encryptedBytes
        }
        val fileId = if (file.effectiveId.isNotBlank()) {
            file.effectiveId
        } else {
            "${resolvedAccId}_${safeFolder}_${rawRemoteName ?: fallbackName}"
        }

        return LocalCloudFile(
            id = fileId,
            name = fallbackName,
            type = if (fallbackAvailable) file.effectiveType else "encrypted",
            size = formatByteCount(fallbackSize),
            rawSize = fallbackSize,
            time = file.time ?: 0L,
            isFolder = false,
            path = if (fallbackAvailable) (file.path ?: "") else "",
            folderId = safeFolder,
            accountId = resolvedAccId,
            mimeType = if (fallbackAvailable) file.mimeType else CryptoConstants.ENCRYPTED_MIME_TYPE,
            cachedAt = now,
            updatedAt = file.updatedAt,
            thumbUrl = null,
            isEncrypted = true,
            encryptionFormatVersion = file.encryptionFormatVersion ?: CryptoConstants.FORMAT_VERSION_V1,
            encryptionKeyId = keyId,
            encryptedMetadata = serializedEnvelope,
            encryptedSizeBytes = encryptedBytes,
            encryptedChecksum = file.encryptedChecksum,
            opaqueRemoteName = rawRemoteName,
            plaintextSizeBytes = file.plaintextSizeBytes,
            originalMetadataAvailable = fallbackAvailable
        )
    }

    /**
     * Re-evaluates all cached encrypted files in Room using the currently available CONTENT_KEY
     * (e.g., immediately after restoring a key from recovery backup).
     */
    suspend fun reevaluateEncryptedMetadata(): Int {
        val provider = contentKeyProvider ?: return 0
        val encryptedRows = db.cloudFileDao().getEncryptedFiles()
        if (encryptedRows.isEmpty()) return 0

        val updatedRows = mutableListOf<LocalCloudFile>()
        for (row in encryptedRows) {
            val metadataJson = row.encryptedMetadata ?: continue
            var keyMaterial: com.cloudyte.app.core.security.ContentKeyMaterial? = null
            try {
                val envelope = metadataCodec.deserializeEnvelopeFromJson(metadataJson)
                keyMaterial = provider.getContentKeyForDecryption(row.encryptionKeyId ?: envelope.keyId)
                val decrypted = metadataCodec.decryptMetadata(envelope, keyMaterial)
                val ext = decrypted.originalExtension.ifBlank {
                    decrypted.originalName.substringAfterLast('.', "")
                }.lowercase(Locale.US)
                val resolvedType = when {
                    ext.isNotBlank() && ext.length <= 6 -> ext
                    decrypted.originalMimeType.isNotBlank() ->
                        decrypted.originalMimeType.substringAfterLast('/', "file").lowercase(Locale.US)
                    else -> "file"
                }
                updatedRows += row.copy(
                    name = decrypted.originalName,
                    type = resolvedType,
                    size = formatByteCount(decrypted.plaintextSizeBytes),
                    rawSize = decrypted.plaintextSizeBytes,
                    mimeType = decrypted.originalMimeType,
                    path = decrypted.relativePath ?: row.path,
                    plaintextSizeBytes = decrypted.plaintextSizeBytes,
                    originalMetadataAvailable = true,
                    thumbUrl = null
                )
            } catch (_: Exception) {
                // Keep row as-is if key still doesn't match or metadata is corrupted
            } finally {
                keyMaterial?.destroy()
            }
        }

        if (updatedRows.isNotEmpty()) {
            db.cloudFileDao().insertFiles(updatedRows)
            FileRepository.cacheFiles(updatedRows.map { it.toCloudFile() })
        }
        return updatedRows.size
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
            parentFolderId = existing.folderId,
            providerType = db.accountDao().getAccountById(normalizedAccount ?: "")?.accountType,
            remoteFileId = fileId,
            payloadJson = "{\"name\":\"${newName.replace("\\", "\\\\").replace("\"", "\\\"")}\"}",
            correlationId = UUID.randomUUID().toString()
        )
        db.withTransaction {
            db.cloudFileDao().updateName(fileId, newName)
            db.fileMutationDao().insert(operation)
            db.folderSyncMetaDao().deleteForFolder(accountMetaKey(normalizedAccount), existing.folderId)
        }
        val fKey = folderKey(normalizedAccount, existing.folderId)
        etagMap.remove(fKey)
        val updatedCloudFile = existing.copy(name = newName).toCloudFile()
        folderMemoryCache.computeIfPresent(fKey) { _, list ->
            list.map { if (it.effectiveId == fileId) updatedCloudFile else it }
        }
        FileRepository.cacheFiles(listOf(updatedCloudFile))
        FileMutationWorker.enqueue()
        return Result.success(Unit)
    }

    suspend fun delete(fileId: String, accountId: String?): Result<Unit> {
        val existing = db.cloudFileDao().getFileById(fileId)
        if (existing != null) {
            recentlyDeletedCache[fileId] = existing
        }
        val normalizedAccount = existing?.accountId ?: accountId?.takeUnless { it == "root" }
        val parentFolderId = existing?.folderId ?: "root"
        val operation = LocalFileMutation(
            operationId = UUID.randomUUID().toString(),
            accountId = normalizedAccount,
            operation = LocalFileMutation.DELETE,
            fileId = fileId,
            parentFolderId = parentFolderId,
            providerType = db.accountDao().getAccountById(normalizedAccount ?: "")?.accountType,
            remoteFileId = fileId,
            payloadJson = "{}",
            correlationId = UUID.randomUUID().toString()
        )
        db.withTransaction {
            db.cloudFileDao().deleteById(fileId)
            db.fileMutationDao().insert(operation)
            db.folderSyncMetaDao().deleteForFolder(accountMetaKey(normalizedAccount), parentFolderId)
        }
        val fKey = folderKey(normalizedAccount, parentFolderId)
        etagMap.remove(fKey)
        folderMemoryCache.computeIfPresent(fKey) { _, list ->
            list.filterNot { it.effectiveId == fileId }
        }
        FileRepository.removeCachedFile(fileId)
        FileMutationWorker.enqueue()
        return Result.success(Unit)
    }

    suspend fun undoDelete(fileId: String, accountId: String?): Result<Unit> {
        return undoDeletes(listOf(fileId), accountId)
    }

    suspend fun undoDeletes(fileIds: List<String>, accountId: String?): Result<Unit> {
        if (fileIds.isEmpty()) return Result.success(Unit)
        val restoredRows = mutableListOf<LocalCloudFile>()
        val affectedFolders = mutableSetOf<Pair<String?, String>>()
        db.withTransaction {
            for (fileId in fileIds) {
                db.fileMutationDao().removeDeletesForFile(fileId)
                val cached = recentlyDeletedCache.remove(fileId)
                if (cached != null) {
                    restoredRows.add(cached)
                    affectedFolders.add(cached.accountId to cached.folderId)
                }
            }
            if (restoredRows.isNotEmpty()) {
                db.cloudFileDao().insertFiles(restoredRows)
            }
        }
        for ((acc, folder) in affectedFolders) {
            val fKey = folderKey(acc, folder)
            etagMap.remove(fKey)
            val current = db.cloudFileDao().getFilesForFolder(acc, folder).map { it.toCloudFile() }
            folderMemoryCache[fKey] = current
            FileRepository.cacheFiles(current)
        }
        val fallbackAcc = accountId?.takeUnless { it == "root" || it == "all" }
        for (fileId in fileIds) {
            val rowAcc = restoredRows.firstOrNull { it.id == fileId }?.accountId ?: fallbackAcc
            if (!rowAcc.isNullOrBlank()) {
                runCatching {
                    api.restoreFromTrash(com.cloudyte.app.api.TrashRestoreRequest(accountId = rowAcc, fileId = fileId))
                }
            }
        }
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
            providerType = db.accountDao().getAccountById(normalizedAccount ?: "")?.accountType,
            payloadJson = "{\"name\":\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"parentFolderId\":\"$safeParentId\"}",
            correlationId = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now
        )
        db.withTransaction {
            db.cloudFileDao().insertFiles(listOf(tempFolder))
            db.fileMutationDao().insert(operation)
            db.folderSyncMetaDao().deleteForFolder(accountMetaKey(normalizedAccount), safeParentId)
        }
        val fKey = folderKey(normalizedAccount, safeParentId)
        etagMap.remove(fKey)
        val newCloudFolder = tempFolder.toCloudFile()
        folderMemoryCache.computeIfPresent(fKey) { _, list ->
            list + newCloudFolder
        }
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

    sealed interface StoredParentResult {
        data class Found(val parentFolderId: String?) : StoredParentResult
        data object NotFound : StoredParentResult
    }

    suspend fun resolveStoredParentFolderId(fileId: String): StoredParentResult {
        val row = db.cloudFileDao().getFileById(fileId) ?: return StoredParentResult.NotFound
        return StoredParentResult.Found(row.folderId.takeUnless { it.isBlank() || it == "root" })
    }

    suspend fun resolveUniqueChildFolderName(
        baseName: String,
        accountId: String?,
        parentFolderId: String?
    ): String {
        val safeParent = safeFolderId(parentFolderId)
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        runCatching { refresh(normalizedAccount, safeParent, force = true) }
        val existingNames = db.cloudFileDao().getFilesForFolder(normalizedAccount, safeParent)
            .filter { it.isFolder }
            .mapTo(mutableSetOf()) { it.name.lowercase(Locale.US) }
        val primary = "$baseName (Encrypted)"
        if (primary.lowercase(Locale.US) !in existingNames) return primary
        var index = 2
        while (true) {
            val candidate = "$baseName (Encrypted $index)"
            if (candidate.lowercase(Locale.US) !in existingNames) return candidate
            index++
        }
    }

    suspend fun createRemoteFolderAndResolve(
        name: String,
        accountId: String?,
        parentFolderId: String?
    ): Result<CloudFile> {
        val safeParent = safeFolderId(parentFolderId)
        val normalizedAccount = if (accountId.isNullOrBlank() || accountId == "root") null else accountId
        val apiParentId = safeParent.takeUnless { it == "root" }
        val existingIds = db.cloudFileDao().getFilesForFolder(normalizedAccount, safeParent)
            .filter { it.isFolder }
            .mapTo(mutableSetOf()) { it.id }

        val response = api.createFolder(normalizedAccount, FolderRequest(name = name, parentId = apiParentId))
        if (!response.isSuccessful) {
            return Result.failure(Exception("Could not create folder '$name' (HTTP ${response.code()})"))
        }
        invalidateFolderCache(normalizedAccount, safeParent)
        val refreshRes = refresh(normalizedAccount, safeParent, force = true)
        if (refreshRes.isFailure) {
            return Result.failure(refreshRes.exceptionOrNull() ?: Exception("Could not refresh folder '$name'"))
        }
        val matches = db.cloudFileDao().getFilesForFolder(normalizedAccount, safeParent)
            .filter { it.isFolder && it.name == name }
        val created = matches.firstOrNull { it.id !in existingIds }
            ?: matches.maxByOrNull { it.time }
            ?: return Result.failure(Exception("Created folder '$name' not found"))
        return Result.success(created.toCloudFile())
    }

    private fun normalizeAccountId(accountId: String?): String {
        return if (accountId.isNullOrBlank()) ALL_ACCOUNTS_KEY else accountId
    }

    private fun nowMillis(): Long = clock()

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

    private suspend fun computeLocalCategoriesAndCount(accountId: String?): Pair<Int, com.cloudyte.app.api.StorageCategories>? {
        val rows = if (accountId == null || accountId == ALL_ACCOUNTS_KEY) {
            db.cloudFileDao().getAllFiles()
        } else {
            db.cloudFileDao().getFilesForAccount(accountId)
        }
        val files = rows.filter { !it.isFolder }.map { it.toCloudFile() }
        if (files.isEmpty()) return null
        var images = 0L
        var documents = 0L
        var videos = 0L
        var archives = 0L
        var audio = 0L
        var other = 0L
        for (file in files) {
            val bytes = file.sizeBytes.coerceAtLeast(0L)
            val ext = file.effectiveType.lowercase(Locale.US)
            when {
                file.isImage -> images += bytes
                file.isVideo -> videos += bytes
                file.isAudio -> audio += bytes
                file.isPdf || file.isTextOrCode || ext in listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "rtf", "odt") ->
                    documents += bytes
                ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso") ->
                    archives += bytes
                else -> other += bytes
            }
        }
        return files.size to com.cloudyte.app.api.StorageCategories(
            images = images,
            documents = documents,
            videos = videos,
            archives = archives,
            audio = audio,
            other = other
        )
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
            val remote = try {
                api.getStorageSummary(apiAccountId)
            } catch (primaryErr: Exception) {
                if (apiAccountId == null) {
                    val accounts = db.accountDao().getAllAccounts().ifEmpty {
                        runCatching { api.getAccounts().map { com.cloudyte.app.db.LocalAccount.fromAccount(it) } }.getOrDefault(emptyList())
                    }
                    if (accounts.isEmpty()) throw primaryErr
                    val perAccount = accounts.mapNotNull { acc ->
                        runCatching { api.getStorageSummary(acc.id) }.getOrNull()
                    }
                    if (perAccount.isEmpty()) throw primaryErr
                    aggregateStorageSummaries(perAccount)
                } else {
                    throw primaryErr
                }
            }

            val enriched = if (remote.categories == null || remote.fileCount == null) {
                val localStats = computeLocalCategoriesAndCount(apiAccountId)
                if (localStats != null) {
                    remote.copy(
                        fileCount = remote.fileCount ?: localStats.first,
                        categories = remote.categories ?: localStats.second
                    )
                } else {
                    remote
                }
            } else {
                remote
            }

            val savedEntity = LocalStorageSummary.fromStorageSummary(
                summary = enriched,
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
