package com.example.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.FolderRequest
import com.example.api.parseBytes
import com.example.ui.screens.UploadHelper
import kotlinx.coroutines.flow.first
import java.util.Locale

class BackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private var totalFilesToProcess = 0
    private var processedFilesCount = 0
    private var uploadedFilesCount = 0
    private var skippedFilesCount = 0
    private var failedFilesCount = 0

    override suspend fun doWork(): Result {
        val context = applicationContext
        SyncLogger.init(context)

        val isManual = inputData.getBoolean("is_manual", false)
        val isEnabled = SyncPreferences.getAutoBackupEnabled(context).first()

        if (!isManual && !isEnabled) {
            SyncLogger.log("Auto-backup skipped (feature is disabled in settings)", SyncLogLevel.INFO)
            return Result.success()
        }

        val uriString = SyncPreferences.getBackupUri(context).first()
        if (uriString.isNullOrBlank()) {
            SyncLogger.log("Backup failed: No local folder selected for backup.", SyncLogLevel.ERROR)
            SyncLogger.updateStatus(SyncState.ERROR, summary = "Failed: No local folder selected")
            return Result.failure()
        }

        val accountId = SyncPreferences.getBackupAccountId(context).first()
        if (accountId.isNullOrBlank()) {
            SyncLogger.log("Backup failed: No target Cloud Drive selected.", SyncLogLevel.ERROR)
            SyncLogger.updateStatus(SyncState.ERROR, summary = "Failed: No Cloud Drive selected")
            return Result.failure()
        }

        val rootUri = Uri.parse(uriString)
        val rootDoc = try {
            DocumentFile.fromTreeUri(context, rootUri)
        } catch (e: Exception) {
            null
        }

        if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
            SyncLogger.log("Backup failed: Cannot access local folder. Permission may have expired.", SyncLogLevel.ERROR)
            SyncLogger.updateStatus(SyncState.ERROR, summary = "Failed: Local folder inaccessible")
            return Result.failure()
        }

        SyncLogger.log("Starting Backup: Local folder '${rootDoc.name ?: "Root"}'", SyncLogLevel.INFO)
        SyncLogger.updateStatus(SyncState.SCANNING, action = "Scanning local files...")

        try {
            // Count total files first
            totalFilesToProcess = countFiles(rootDoc)
            processedFilesCount = 0
            uploadedFilesCount = 0
            skippedFilesCount = 0
            failedFilesCount = 0

            SyncLogger.log("Found $totalFilesToProcess total file(s) to check.", SyncLogLevel.INFO)
            SyncLogger.updateStatus(
                SyncState.SYNCING,
                action = "Starting sync...",
                uploadedCount = 0,
                totalCount = totalFilesToProcess
            )

            syncDirectory(context, accountId, null, rootDoc, "")

            val summary = if (failedFilesCount == 0) {
                "Completed: $uploadedFilesCount uploaded, $skippedFilesCount up-to-date"
            } else {
                "Finished with $failedFilesCount error(s). $uploadedFilesCount uploaded, $skippedFilesCount up-to-date"
            }

            val finalState = if (failedFilesCount > 0 && uploadedFilesCount == 0 && skippedFilesCount == 0) SyncState.ERROR else SyncState.SUCCESS
            val finalLogLevel = if (failedFilesCount == 0) SyncLogLevel.SUCCESS else SyncLogLevel.WARNING

            SyncLogger.log("✓ $summary", finalLogLevel)
            SyncLogger.updateStatus(
                state = finalState,
                action = "Idle",
                uploadedCount = uploadedFilesCount,
                totalCount = totalFilesToProcess,
                summary = summary
            )

            return Result.success()
        } catch (e: Exception) {
            Log.e("BackupWorker", "Backup failed with exception", e)
            val errMsg = e.localizedMessage ?: e.message ?: "Unknown error"
            SyncLogger.log("✗ Sync interrupted: $errMsg", SyncLogLevel.ERROR)
            SyncLogger.updateStatus(SyncState.ERROR, summary = "Interrupted: $errMsg")
            return Result.retry()
        }
    }

    private fun countFiles(dir: DocumentFile): Int {
        var count = 0
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                count += countFiles(file)
            } else if (file.isFile) {
                count++
            }
        }
        return count
    }

    private suspend fun syncDirectory(
        context: Context,
        accountId: String,
        currentCloudFolderId: String?,
        localDir: DocumentFile,
        currentPath: String
    ) {
        val folderName = localDir.name ?: "Root"
        val pathLabel = if (currentPath.isBlank()) folderName else "$currentPath/$folderName"

        val cloudFiles = try {
            ApiClient.apiService.getFiles(accountId, currentCloudFolderId, null)
        } catch (e: Exception) {
            SyncLogger.log("Failed to fetch cloud files for '$pathLabel': ${e.message}", SyncLogLevel.WARNING)
            emptyList()
        }

        val localItems = localDir.listFiles()

        // 1. Process files first
        for (file in localItems) {
            if (file.isFile) {
                val fileName = file.name ?: continue
                processedFilesCount++
                val fileSize = file.length()
                val sizeFormatted = formatFileSize(fileSize)

                val alreadyUploaded = cloudFiles.any { !it.isFolderItem && it.effectiveName == fileName }
                if (alreadyUploaded) {
                    skippedFilesCount++
                    SyncLogger.log("• [$processedFilesCount/$totalFilesToProcess] '$fileName' is already up to date on Drive.", SyncLogLevel.INFO)
                    SyncLogger.updateStatus(
                        SyncState.SYNCING,
                        action = "Skipping up-to-date files...",
                        file = fileName,
                        uploadedCount = uploadedFilesCount,
                        totalCount = totalFilesToProcess
                    )
                } else {
                    SyncLogger.log("↑ [$processedFilesCount/$totalFilesToProcess] Uploading '$fileName' ($sizeFormatted)...", SyncLogLevel.INFO)
                    SyncLogger.updateStatus(
                        SyncState.SYNCING,
                        action = "Uploading: $fileName ($sizeFormatted)",
                        file = fileName,
                        uploadedCount = uploadedFilesCount,
                        totalCount = totalFilesToProcess
                    )

                    val uploadResult = UploadHelper.uploadFile(
                        context = context,
                        accountId = accountId,
                        folderId = currentCloudFolderId,
                        uri = file.uri,
                        explicitFileName = fileName,
                        explicitMimeType = file.type
                    )

                    if (uploadResult.isSuccess) {
                        uploadedFilesCount++
                        SyncLogger.log("✓ Uploaded '$fileName' successfully", SyncLogLevel.SUCCESS)
                    } else {
                        failedFilesCount++
                        SyncLogger.log("✗ Failed '$fileName': ${uploadResult.errorMessage ?: "Upload failed"}", SyncLogLevel.ERROR)
                    }

                    SyncLogger.updateStatus(
                        SyncState.SYNCING,
                        action = "Syncing files...",
                        file = fileName,
                        uploadedCount = uploadedFilesCount,
                        totalCount = totalFilesToProcess
                    )
                }
            }
        }

        // 2. Process subdirectories recursively
        for (file in localItems) {
            if (file.isDirectory) {
                val subFolderName = file.name ?: continue
                var cloudFolder = cloudFiles.find { it.isFolderItem && it.effectiveName == subFolderName }
                if (cloudFolder == null) {
                    SyncLogger.log("Creating cloud folder: '$subFolderName'...", SyncLogLevel.INFO)
                    try {
                        ApiClient.apiService.createFolder(accountId, FolderRequest(subFolderName, currentCloudFolderId))
                        val updatedFiles = ApiClient.apiService.getFiles(accountId, currentCloudFolderId, null)
                        cloudFolder = updatedFiles.find { it.isFolderItem && it.effectiveName == subFolderName }
                    } catch (e: Exception) {
                        SyncLogger.log("Failed to create folder '$subFolderName': ${e.message}", SyncLogLevel.ERROR)
                    }
                }

                if (cloudFolder != null) {
                    syncDirectory(context, accountId, cloudFolder.effectiveId, file, pathLabel)
                }
            }
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0L) return "0 B"
        return when {
            size >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", size.toDouble() / (1024L * 1024 * 1024))
            size >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", size.toDouble() / (1024L * 1024))
            size >= 1024 -> String.format(Locale.US, "%.1f KB", size.toDouble() / 1024)
            else -> "$size B"
        }
    }
}
