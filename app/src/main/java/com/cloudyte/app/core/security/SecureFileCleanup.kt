package com.cloudyte.app.core.security

import android.content.Context
import java.io.File

/**
 * Manages lifecycle and cleanup of encrypted upload staging files, encrypted/plaintext download
 * temporary files, and controlled viewer decrypted cache files.
 */
object SecureFileCleanup {

    private const val STALE_TEMP_MAX_AGE_MS = 6L * 60L * 60L * 1000L // 6 hours
    private const val VIEW_CACHE_MAX_AGE_MS = 30L * 60L * 1000L // 30 minutes

    fun getEncryptedStagingDir(context: Context): File {
        val dir = File(context.cacheDir, CryptoConstants.ENCRYPTED_STAGING_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDownloadStagingDir(context: Context): File {
        val dir = File(context.cacheDir, CryptoConstants.DOWNLOAD_STAGING_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDecryptedViewCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CryptoConstants.DECRYPTED_VIEW_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getStagingFileForTransfer(context: Context, transferId: String): File {
        return File(getEncryptedStagingDir(context), "$transferId.cyte")
    }

    fun getStagingTempFileForTransfer(context: Context, transferId: String): File {
        return File(getEncryptedStagingDir(context), "$transferId.cyte.tmp")
    }

    fun getEncryptedDownloadPartFile(context: Context, transferId: String): File {
        return File(getDownloadStagingDir(context), "$transferId.cyte.part")
    }

    fun getPlaintextDownloadTempFile(context: Context, transferId: String): File {
        return File(getDownloadStagingDir(context), "$transferId.plaintext.tmp")
    }

    /**
     * Deletes both `.cyte.tmp` and `.cyte` staging files for a transfer (plus any custom stage path).
     */
    fun cleanupTransferStaging(
        context: Context,
        transferId: String,
        customStagePath: String? = null
    ) {
        deleteFileSafely(getStagingTempFileForTransfer(context, transferId))
        deleteFileSafely(getStagingFileForTransfer(context, transferId))
        if (!customStagePath.isNullOrBlank()) {
            deleteFileSafely(File(customStagePath))
            deleteFileSafely(File("$customStagePath.tmp"))
        }
    }

    /**
     * Deletes download `.cyte.part` and `.plaintext.tmp` temporary files for a transfer.
     */
    fun cleanupDownloadTemp(
        context: Context,
        transferId: String,
         removePartFile: Boolean = true
    ) {
        deleteFileSafely(getPlaintextDownloadTempFile(context, transferId))
        if (removePartFile) {
            deleteFileSafely(getEncryptedDownloadPartFile(context, transferId))
        }
    }

    /**
     * Cleans up any stale `.tmp`, `.part`, or orphaned staging files on app startup or periodic
     * cache maintenance, preserving files belonging to [activeTransferIds].
     */
    fun cleanupStaleTempFiles(
        context: Context,
        activeTransferIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        var deletedCount = 0

        val stagingDir = File(context.cacheDir, CryptoConstants.ENCRYPTED_STAGING_DIR)
        if (stagingDir.exists()) {
            stagingDir.listFiles()?.forEach { file ->
                val name = file.name
                val matchesActive = activeTransferIds.any { id ->
                    name == "$id.cyte" || name == "$id.cyte.tmp"
                }
                val isTmp = name.endsWith(".tmp")
                val isStale = (nowMs - file.lastModified()) > STALE_TEMP_MAX_AGE_MS
                if (!matchesActive && (isTmp || activeTransferIds.isEmpty() || isStale)) {
                    if (deleteFileSafely(file)) deletedCount++
                }
            }
        }

        val downloadStagingDir = File(context.cacheDir, CryptoConstants.DOWNLOAD_STAGING_DIR)
        if (downloadStagingDir.exists()) {
            downloadStagingDir.listFiles()?.forEach { file ->
                val name = file.name
                val matchesActive = activeTransferIds.any { id ->
                    name.startsWith("$id.")
                }
                val isPlaintextTmp = name.endsWith(".plaintext.tmp") || name.endsWith(".tmp")
                val isStale = (nowMs - file.lastModified()) > STALE_TEMP_MAX_AGE_MS
                if (isPlaintextTmp && !matchesActive) {
                    if (deleteFileSafely(file)) deletedCount++
                } else if (!matchesActive && (activeTransferIds.isEmpty() || isStale)) {
                    if (deleteFileSafely(file)) deletedCount++
                }
            }
        }

        val viewCacheDir = File(context.cacheDir, CryptoConstants.DECRYPTED_VIEW_CACHE_DIR)
        if (viewCacheDir.exists()) {
            viewCacheDir.listFiles()?.forEach { file ->
                val isTmp = file.name.endsWith(".tmp")
                val isStale = (nowMs - file.lastModified()) > VIEW_CACHE_MAX_AGE_MS
                if (isTmp || isStale || activeTransferIds.isEmpty()) {
                    if (deleteFileSafely(file)) deletedCount++
                }
            }
        }

        val offlineVaultDir = File(context.filesDir, "offline_vault")
        if (offlineVaultDir.exists()) {
            offlineVaultDir.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.name.endsWith(".tmp") || file.name.endsWith(".part")) {
                    val isStale = (nowMs - file.lastModified()) > STALE_TEMP_MAX_AGE_MS
                    if (activeTransferIds.isEmpty() || isStale) {
                        if (deleteFileSafely(file)) deletedCount++
                    }
                }
            }
        }

        return deletedCount
    }

    /**
     * Purges all temporary decrypted viewer files immediately.
     */
    fun clearDecryptedViewCache(context: Context) {
        val dir = File(context.cacheDir, CryptoConstants.DECRYPTED_VIEW_CACHE_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { deleteFileSafely(it) }
        }
    }

    fun deleteFileSafely(file: File?): Boolean {
        if (file == null) return false
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
