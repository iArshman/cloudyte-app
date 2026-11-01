package com.cloudyte.app.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.data.CloudFileRepository
import com.cloudyte.app.db.AppDatabase
import java.util.concurrent.TimeUnit

class FolderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val folderId = inputData.getString(KEY_FOLDER_ID) ?: "root"
        val repository = CloudFileRepository(AppDatabase.getDatabase(applicationContext), ApiClient.apiService)
        return if (repository.refresh(accountId, folderId, force = true).isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_PREFIX = "cloudyte_folder_sync_"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_FOLDER_ID = "folder_id"

        fun enqueue(context: Context, accountId: String?, folderId: String = "root") {
            val data = Data.Builder().putString(KEY_FOLDER_ID, folderId).apply {
                if (accountId != null) putString(KEY_ACCOUNT_ID, accountId)
            }.build()
            val request = OneTimeWorkRequestBuilder<FolderSyncWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "$UNIQUE_PREFIX${accountId ?: "null"}:$folderId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
