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
import com.example.ui.screens.UploadHelper
import kotlinx.coroutines.flow.first

class BackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val isEnabled = SyncPreferences.getAutoBackupEnabled(context).first()
        if (!isEnabled) {
            return Result.success()
        }

        val uriString = SyncPreferences.getBackupUri(context).first() ?: return Result.success()
        val accountId = SyncPreferences.getBackupAccountId(context).first() ?: return Result.success()

        val rootUri = Uri.parse(uriString)
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
        if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
            return Result.failure()
        }

        try {
            syncDirectory(context, accountId, null, rootDoc)
            return Result.success()
        } catch (e: Exception) {
            Log.e("BackupWorker", "Sync failed", e)
            return Result.retry()
        }
    }

    private suspend fun syncDirectory(context: Context, accountId: String, currentCloudFolderId: String?, localDir: DocumentFile) {
        val cloudFiles = ApiClient.apiService.getFiles(accountId, currentCloudFolderId, null)

        localDir.listFiles().forEach { file ->
            if (file.isDirectory) {
                val folderName = file.name ?: return@forEach
                var cloudFolder = cloudFiles.find { it.isFolderItem && it.effectiveName == folderName }
                if (cloudFolder == null) {
                    ApiClient.apiService.createFolder(accountId, FolderRequest(folderName, currentCloudFolderId))
                    val updatedFiles = ApiClient.apiService.getFiles(accountId, currentCloudFolderId, null)
                    cloudFolder = updatedFiles.find { it.isFolderItem && it.effectiveName == folderName }
                }

                if (cloudFolder != null) {
                    syncDirectory(context, accountId, cloudFolder.effectiveId, file)
                }
            } else {
                val fileName = file.name ?: return@forEach
                val cloudFile = cloudFiles.find { !it.isFolderItem && it.effectiveName == fileName }
                if (cloudFile == null) {
                    UploadHelper.uploadFile(context, accountId, currentCloudFolderId, file.uri)
                }
            }
        }
    }
}
