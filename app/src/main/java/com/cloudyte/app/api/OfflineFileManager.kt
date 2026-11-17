package com.cloudyte.app.api

import android.content.Context
import android.util.Log
import com.cloudyte.app.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object OfflineFileManager {
    private const val TAG = "OfflineFileManager"
    private const val OFFLINE_DIR_NAME = "offline_vault"

    private val _downloadedFiles = MutableStateFlow<Set<String>>(emptySet())
    val downloadedFiles: StateFlow<Set<String>> = _downloadedFiles.asStateFlow()

    // Map of fileId to download progress (0.0f .. 1.0f)
    private val _activeDownloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, Float>> = _activeDownloads.asStateFlow()

    private val downloadingSet = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        scope.launch {
            refreshDownloadedSetAsync(context)
        }
    }

    fun getInternalVaultDir(context: Context): File {
        val dir = File(context.filesDir, OFFLINE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getExternalVaultDir(context: Context): File? {
        val ext = context.getExternalFilesDir(OFFLINE_DIR_NAME) ?: return null
        if (!ext.exists()) ext.mkdirs()
        return ext
    }

    fun getAllVaultDirs(context: Context): List<File> {
        val list = mutableListOf(getInternalVaultDir(context))
        getExternalVaultDir(context)?.let { ext ->
            if (ext.absolutePath != list.first().absolutePath) {
                list += ext
            }
        }
        return list
    }

    suspend fun getPreferredVaultDir(context: Context): File {
        val pref = runCatching {
            UserPreferences.observe(context).first().downloadDirectory
        }.getOrDefault("INTERNAL")
        return if (pref.equals("EXTERNAL", ignoreCase = true)) {
            getExternalVaultDir(context) ?: getInternalVaultDir(context)
        } else {
            getInternalVaultDir(context)
        }
    }

    fun getPreferredVaultDirSync(context: Context, directorySetting: String): File {
        return if (directorySetting.equals("EXTERNAL", ignoreCase = true)) {
            getExternalVaultDir(context) ?: getInternalVaultDir(context)
        } else {
            getInternalVaultDir(context)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    fun getLocalFile(
        context: Context,
        accountId: String,
        fileId: String,
        fileName: String,
        vaultDir: File = getInternalVaultDir(context)
    ): File {
        val safeAcc = sanitizeFileName(accountId)
        val safeFid = sanitizeFileName(fileId)
        val safeName = sanitizeFileName(fileName)
        return File(vaultDir, "${safeAcc}_${safeFid}_$safeName")
    }

    fun findOfflineFile(context: Context, accountId: String, fileId: String): File? {
        val safeFid = sanitizeFileName(fileId)
        if (safeFid.isBlank()) return null

        val dirs = getAllVaultDirs(context)
        val safeAcc = sanitizeFileName(accountId)
        if (accountId.isNotBlank() && accountId != "all" && accountId != "root" && safeAcc.isNotBlank()) {
            val prefix = "${safeAcc}_${safeFid}_"
            for (dir in dirs) {
                val matches = dir.listFiles { _, name ->
                    name.startsWith(prefix) && !name.endsWith(".tmp") && !name.endsWith(".part")
                }
                val found = matches?.firstOrNull { it.exists() && it.length() > 0 }
                if (found != null) return found
            }
        }

        // Fallback: if downloadedFiles was populated using DB account tuples or another account
        // prefix (for example when opening from All Drives / Recent / mismatched accountId),
        // locate the matching offline_vault file by bounded _<safeFid>_ token.
        val fidToken = "_${safeFid}_"
        for (dir in dirs) {
            val matches = dir.listFiles { _, name ->
                !name.endsWith(".tmp") &&
                    !name.endsWith(".part") &&
                    name.contains(fidToken)
            }
            val found = matches?.firstOrNull { it.exists() && it.length() > 0 }
            if (found != null) return found
        }
        return null
    }

    fun markDownloaded(fileId: String) {
        if (fileId.isBlank()) return
        _downloadedFiles.value = _downloadedFiles.value + fileId
        _activeDownloads.value = _activeDownloads.value - fileId
        downloadingSet.remove(fileId)
    }

    fun isDownloaded(context: Context, accountId: String, fileId: String): Boolean {
        if (_downloadedFiles.value.contains(fileId)) return true
        val file = findOfflineFile(context, accountId, fileId)
        val exists = file != null && file.exists() && file.length() > 0
        if (exists && !_downloadedFiles.value.contains(fileId)) {
            _downloadedFiles.value = _downloadedFiles.value + fileId
        }
        return exists
    }

    fun isDownloading(fileId: String): Boolean {
        return downloadingSet.contains(fileId)
    }

    fun getDownloadProgress(fileId: String): Float? {
        return _activeDownloads.value[fileId]
    }

    suspend fun refreshDownloadedSetAsync(context: Context) = withContext(Dispatchers.IO) {
        val vaultFiles = getAllVaultDirs(context).flatMap { dir ->
            dir.listFiles { _, name -> !name.endsWith(".tmp") && !name.endsWith(".part") }
                ?.filter { it.isFile && it.length() > 0L }
                ?: emptyList()
        }
        if (vaultFiles.isEmpty()) {
            _downloadedFiles.value = emptySet()
            return@withContext
        }

        val set = mutableSetOf<String>()
        val db = com.cloudyte.app.db.AppDatabase.getInstance()
        val knownAccountPrefixes = mutableListOf<String>()
        val knownFileMatches = mutableListOf<Pair<String, String>>() // safePrefix -> raw fileId

        if (db != null) {
            runCatching {
                db.accountDao().getAllAccounts().forEach { acc ->
                    knownAccountPrefixes += "${sanitizeFileName(acc.id)}_"
                }
                db.cloudFileDao().getFileAccountIdTuples().forEach { tuple ->
                    val acc = tuple.accountId?.takeIf { it.isNotBlank() } ?: return@forEach
                    val prefix = "${sanitizeFileName(acc)}_${sanitizeFileName(tuple.id)}_"
                    knownFileMatches += prefix to tuple.id
                }
            }
        }
        val sortedAccountPrefixes = knownAccountPrefixes.distinct().sortedByDescending { it.length }
        val sortedFileMatches = knownFileMatches.sortedByDescending { it.first.length }

        for (f in vaultFiles) {
            val matchedKnownFile = sortedFileMatches.firstOrNull { (prefix, _) -> f.name.startsWith(prefix) }
            if (matchedKnownFile != null) {
                set.add(matchedKnownFile.second)
                continue
            }
            val matchedAccPrefix = sortedAccountPrefixes.firstOrNull { prefix -> f.name.startsWith(prefix) }
            if (matchedAccPrefix != null) {
                val remainder = f.name.removePrefix(matchedAccPrefix)
                val fid = remainder.substringBefore("_")
                if (fid.isNotEmpty()) {
                    set.add(fid)
                    continue
                }
            }
            val parts = f.name.split("_")
            if (parts.size >= 3) {
                set.add(parts[1])
            }
        }
        _downloadedFiles.value = set
    }

    fun refreshDownloadedSet(context: Context) {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            refreshDownloadedSetAsync(context)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun autoCacheOnView(context: Context, accountId: String, file: CloudFile) {
        // Browsing/opening files uses ephemeral PreviewStore/Coil/ExoPlayer caches rather than
        // creating persistent TransferDao download rows or triggering ActiveTransferPill popups.
    }

    suspend fun downloadFile(
        context: Context,
        accountId: String,
        file: CloudFile,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val fid = file.effectiveId
        val fileName = file.effectiveName.ifBlank { "file_${fid}" }
        val existing = findOfflineFile(context, accountId, fid)
        if (existing != null && existing.exists() && existing.length() > 0) {
            _downloadedFiles.value = _downloadedFiles.value + fid
            onProgress?.invoke(1f)
            return@withContext Result.success(existing)
        }

        val vaultDir = getPreferredVaultDir(context)
        val targetFile = getLocalFile(context, accountId, fid, fileName, vaultDir)

        if (targetFile.exists() && targetFile.length() > 0) {
            _downloadedFiles.value = _downloadedFiles.value + fid
            onProgress?.invoke(1f)
            return@withContext Result.success(targetFile)
        }

        if (!downloadingSet.add(fid)) {
            return@withContext Result.failure(Exception("Download already in progress"))
        }

        _activeDownloads.value = _activeDownloads.value + (fid to 0f)

        val legacyTempFile = File(vaultDir, "${targetFile.name}.tmp")
        try {
            if (legacyTempFile.exists()) legacyTempFile.delete()
            val app = context.applicationContext as? com.cloudyte.app.CloudyteApplication
                ?: throw IllegalStateException("Application transfer queue is unavailable")
            // CloudTransferOperationExecutor already downloads into a .part staging file and
            // atomically commits to destinationPath upon verification, so targeting targetFile
            // directly ensures background-completed offline transfers are immediately playable.
            val transferId = app.container.transferRepository.enqueueOrGetOfflineDownload(
                accountId = accountId,
                fileId = fid,
                fileName = fileName,
                destinationPath = targetFile.absolutePath,
                totalBytes = (if (file.isEncrypted) file.encryptedSizeBytes ?: file.sizeBytes else file.sizeBytes).coerceAtLeast(0L),
                isEncrypted = file.isEncrypted,
                encryptionFormatVersion = file.encryptionFormatVersion,
                encryptionKeyId = file.encryptionKeyId,
                encryptedSizeBytes = file.encryptedSizeBytes,
                encryptedChecksum = file.encryptedChecksum,
                opaqueRemoteName = file.opaqueRemoteName,
                plaintextSizeBytes = file.plaintextSizeBytes,
                originalMetadataAvailable = file.originalMetadataAvailable
            )
            app.container.transferCoordinator.dispatch(com.cloudyte.app.core.transfer.TransferCommand.Start(transferId))
            val completed = kotlinx.coroutines.withTimeoutOrNull(30 * 60 * 1000L) {
                var finished = false
                app.container.database.transferDao().observeById(transferId).first { row ->
                    if (row == null) throw IOException("Queued download disappeared")
                    val state = row.state
                    val total = row.totalBytes
                    val progress = if (total > 0L) (row.bytesTransferred.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _activeDownloads.value = _activeDownloads.value + (fid to progress)
                    onProgress?.invoke(progress)
                    when (state) {
                        "COMPLETED" -> {
                            finished = true
                            true
                        }
                        "FAILED_RETRYABLE", "FAILED_PERMANENT", "WAITING_FOR_AUTH", "CANCELLED", "EXPIRED" ->
                            throw IOException(row.lastErrorMessage ?: row.errorMessage ?: "Download failed ($state)")
                        else -> false
                    }
                }
                finished
            } ?: throw IOException("Download is still queued; try again shortly")
            val finalFile = when {
                targetFile.exists() && targetFile.length() > 0L -> targetFile
                legacyTempFile.exists() && legacyTempFile.length() > 0L -> {
                    if (legacyTempFile.renameTo(targetFile)) targetFile
                    else {
                        legacyTempFile.copyTo(targetFile, overwrite = true)
                        legacyTempFile.delete()
                        targetFile
                    }
                }
                else -> findOfflineFile(context, accountId, fid)
            }
            if (completed && finalFile != null && finalFile.exists() && finalFile.length() > 0L) {
                markDownloaded(fid)
                onProgress?.invoke(1f)
                return@withContext Result.success(finalFile)
            }
            throw IOException("Queued download completed without a local file")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e::class.simpleName}")
            if (legacyTempFile.exists()) legacyTempFile.delete()
            _activeDownloads.value = _activeDownloads.value - fid
            downloadingSet.remove(fid)
            return@withContext Result.failure(e)
        }
    }

    suspend fun prepareDecryptedFileForViewer(
        context: Context,
        accountId: String,
        file: CloudFile,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val fid = file.effectiveId
        if (fid.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("File ID is empty"))
        }

        val offlineFile = findOfflineFile(context, accountId, fid)
            ?: downloadFile(context, accountId, file, onProgress).getOrElse { err ->
                return@withContext Result.failure(err)
            }

        val streamCrypto = com.cloudyte.app.core.security.StreamFileCrypto()
        if (!streamCrypto.hasCyteMagic(offlineFile)) {
            onProgress?.invoke(1f)
            return@withContext Result.success(offlineFile)
        }

        val app = context.applicationContext as? com.cloudyte.app.CloudyteApplication
        val keyProvider = app?.container?.contentKeyManager
            ?: com.cloudyte.app.core.security.ContentKeyManager(context)
        val viewCacheDir = com.cloudyte.app.core.security.SecureFileCleanup.getDecryptedViewCacheDir(context)
        val safeAcc = sanitizeFileName(accountId)
        val safeFid = sanitizeFileName(fid)
        val safeName = sanitizeFileName(file.effectiveName.ifBlank { "decrypted_$fid" })
        val viewTargetFile = File(viewCacheDir, "view_${safeAcc}_${safeFid}_$safeName")
        val viewTempFile = File(viewCacheDir, "view_${safeAcc}_${safeFid}_$safeName.tmp")

        var keyMaterial: com.cloudyte.app.core.security.ContentKeyMaterial? = null
        try {
            val header = streamCrypto.validateEncryptedFile(offlineFile, expectedKeyId = file.encryptionKeyId)
            keyMaterial = keyProvider.getContentKeyForDecryption(file.encryptionKeyId ?: header.keyId)
            streamCrypto.decryptEncryptedFileToPlaintext(
                encryptedFile = offlineFile,
                destinationTempFile = viewTempFile,
                keyMaterial = keyMaterial,
                onProgress = { written ->
                    if (header.plaintextSize > 0L) {
                        onProgress?.invoke((written.toFloat() / header.plaintextSize.toFloat()).coerceIn(0f, 1f))
                    }
                }
            )
            if (viewTargetFile.exists()) viewTargetFile.delete()
            if (!viewTempFile.renameTo(viewTargetFile)) {
                viewTempFile.copyTo(viewTargetFile, overwrite = true)
                viewTempFile.delete()
            }
            onProgress?.invoke(1f)
            Result.success(viewTargetFile)
        } catch (e: Exception) {
            com.cloudyte.app.core.security.SecureFileCleanup.deleteFileSafely(viewTempFile)
            com.cloudyte.app.core.security.SecureFileCleanup.deleteFileSafely(viewTargetFile)
            Result.failure(e)
        } finally {
            keyMaterial?.destroy()
        }
    }

    fun deleteOfflineFile(context: Context, accountId: String, fileId: String): Boolean {
        val file = findOfflineFile(context, accountId, fileId)
        val deleted = file?.delete() ?: false
        _downloadedFiles.value = _downloadedFiles.value - fileId
        val safeAcc = sanitizeFileName(accountId)
        val safeFid = sanitizeFileName(fileId)
        val viewCacheDir = com.cloudyte.app.core.security.SecureFileCleanup.getDecryptedViewCacheDir(context)
        viewCacheDir.listFiles { _, name -> name.startsWith("view_${safeAcc}_${safeFid}_") }?.forEach {
            com.cloudyte.app.core.security.SecureFileCleanup.deleteFileSafely(it)
        }
        return deleted
    }

    fun deleteOfflineFilesForAccount(context: Context, accountId: String): Int {
        val safeAcc = sanitizeFileName(accountId)
        val prefix = "${safeAcc}_"
        var deletedCount = 0
        for (dir in getAllVaultDirs(context)) {
            dir.listFiles { _, name -> name.startsWith(prefix) }?.forEach { file ->
                if (com.cloudyte.app.core.security.SecureFileCleanup.deleteFileSafely(file)) {
                    deletedCount++
                }
            }
        }
        val viewCacheDir = com.cloudyte.app.core.security.SecureFileCleanup.getDecryptedViewCacheDir(context)
        viewCacheDir.listFiles { _, name -> name.startsWith("view_${safeAcc}_") }?.forEach { file ->
            com.cloudyte.app.core.security.SecureFileCleanup.deleteFileSafely(file)
        }
        return deletedCount
    }

    fun getOfflineCacheSize(context: Context): Long {
        var total = 0L
        for (dir in getAllVaultDirs(context)) {
            dir.listFiles()?.forEach {
                if (it.isFile) total += it.length()
            }
        }
        return total
    }

    fun getOfflineCount(context: Context): Int {
        var count = 0
        for (dir in getAllVaultDirs(context)) {
            count += dir.listFiles { _, name -> !name.endsWith(".tmp") && !name.endsWith(".part") }
                ?.count { it.isFile && it.length() > 0 }
                ?: 0
        }
        return count
    }

    fun clearAllOfflineCache(context: Context): Int {
        var deletedCount = 0
        for (dir in getAllVaultDirs(context)) {
            dir.listFiles()?.forEach { file ->
                if (file.delete()) deletedCount++
            }
        }
        com.cloudyte.app.core.security.SecureFileCleanup.clearDecryptedViewCache(context)
        _downloadedFiles.value = emptySet()
        _activeDownloads.value = emptyMap()
        return deletedCount
    }
}
