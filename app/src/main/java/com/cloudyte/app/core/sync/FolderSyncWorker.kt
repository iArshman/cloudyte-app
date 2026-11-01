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
import com.cloudyte.app.data.providers.ProviderRegistry
import com.cloudyte.app.db.AppDatabase
import java.util.concurrent.TimeUnit

class FolderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val folderId = inputData.getString(KEY_FOLDER_ID) ?: "root"
        val providerType = inputData.getString(KEY_PROVIDER_TYPE)
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = CloudFileRepository(database, ApiClient.apiService)
        val result = repository.refresh(
            accountId = accountId,
            folderId = folderId,
            force = true,
            providerTypeHint = providerType
        )
        return if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_PREFIX = "cloudyte_folder_sync_"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_PROVIDER_TYPE = "provider_type"

        fun uniqueWorkName(
            accountId: String?,
            providerType: String? = null,
            folderId: String = "root"
        ): String {
            val normalizedProvider = ProviderRegistry.normalize(providerType).ifBlank { "unknown" }
            val safeAccount = accountId?.takeIf { it.isNotBlank() } ?: "null"
            val safeFolder = folderId.ifBlank { "root" }
            return "$UNIQUE_PREFIX$safeAccount:$normalizedProvider:$safeFolder"
        }

        @JvmOverloads
        fun enqueue(
            context: Context,
            accountId: String?,
            folderId: String = "root",
            providerType: String? = null
        ) {
            val normalizedProvider = ProviderRegistry.normalize(providerType).ifBlank { "unknown" }
            val data = Data.Builder()
                .putString(KEY_FOLDER_ID, folderId)
                .putString(KEY_PROVIDER_TYPE, normalizedProvider)
                .apply {
                    if (accountId != null) putString(KEY_ACCOUNT_ID, accountId)
                }
                .build()
            val request = OneTimeWorkRequestBuilder<FolderSyncWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueWorkName(accountId = accountId, providerType = normalizedProvider, folderId = folderId),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

