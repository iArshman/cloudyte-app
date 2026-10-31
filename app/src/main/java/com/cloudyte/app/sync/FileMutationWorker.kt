package com.cloudyte.app.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.FolderRequest
import com.cloudyte.app.api.RenameRequest
import com.cloudyte.app.data.CloudFileRepository
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalFileMutation
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

class FileMutationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.fileMutationDao()
        val repository = CloudFileRepository(database, ApiClient.apiService)

        for (mutation in dao.pending()) {
            try {
                val response = when (mutation.operation) {
                    LocalFileMutation.RENAME -> ApiClient.apiService.renameFile(
                        mutation.fileId ?: return Result.failure(),
                        mutation.accountId,
                        RenameRequest(mutation.name ?: return Result.failure())
                    )
                    LocalFileMutation.DELETE -> ApiClient.apiService.deleteFile(
                        mutation.fileId ?: return Result.failure(),
                        mutation.accountId
                    )
                    LocalFileMutation.CREATE_FOLDER -> ApiClient.apiService.createFolder(
                        mutation.accountId,
                        FolderRequest(
                            mutation.name ?: return Result.failure(),
                            mutation.parentFolderId.takeUnless { it == "root" }
                        )
                    )
                    else -> {
                        dao.markPermanent(mutation.operationId, "Unsupported queued operation")
                        continue
                    }
                }

                if (response.isSuccessful) {
                    dao.remove(mutation.operationId)
                    repository.invalidateFolderCache(mutation.accountId, mutation.parentFolderId)
                    repository.refresh(mutation.accountId, mutation.parentFolderId, force = true)
                } else {
                    val status = response.code()
                    val error = "HTTP $status: ${response.errorBody()?.string()?.take(300).orEmpty()}"
                    if (isPermanentClientError(status)) {
                        dao.remove(mutation.operationId)
                        mutation.optimisticFileId?.let { database.cloudFileDao().deleteById(it) }
                        repository.invalidateFolderCache(mutation.accountId, mutation.parentFolderId)
                        repository.refresh(mutation.accountId, mutation.parentFolderId, force = true)
                    } else {
                        dao.recordRetry(mutation.operationId, error)
                        return Result.retry()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (isPermanentClientError(e.code())) {
                    dao.remove(mutation.operationId)
                    mutation.optimisticFileId?.let { database.cloudFileDao().deleteById(it) }
                    repository.invalidateFolderCache(mutation.accountId, mutation.parentFolderId)
                    repository.refresh(mutation.accountId, mutation.parentFolderId, force = true)
                } else {
                    dao.recordRetry(mutation.operationId, e.message())
                    return Result.retry()
                }
            } catch (e: Exception) {
                dao.recordRetry(mutation.operationId, e.message ?: "Network error")
                return Result.retry()
            }
        }
        return Result.success()
    }

    private fun isPermanentClientError(status: Int): Boolean =
        status in 400..499 && status !in setOf(401, 403, 408, 429)

    companion object {
        private const val UNIQUE_WORK_NAME = "cloudyte_file_mutation_outbox"
        private const val TAG = "FileMutationWorker"

        @Volatile
        private var appContext: Context? = null

        fun initialize(context: Context) {
            appContext = context.applicationContext
        }

        fun enqueue() {
            val context = appContext ?: return
            try {
                val request = OneTimeWorkRequestBuilder<FileMutationWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not schedule file-mutation replay; entries remain in Room", e)
            }
        }
    }
}
