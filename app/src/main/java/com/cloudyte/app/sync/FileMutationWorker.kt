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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class FileMutationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.fileMutationDao()
        val repository = (applicationContext as? com.cloudyte.app.CloudyteApplication)?.container?.cloudFileRepository
            ?: CloudFileRepository(database, ApiClient.apiService)
        var hasRetryableWork = false

        // The list is account-grouped and creation-ordered. Processing it serially preserves
        // mutation order and guarantees no two operations for one remote file overlap.
        for (mutation in dao.pendingDue()) {
            if (isStopped) {
                hasRetryableWork = true
                break
            }
            if (dao.claim(mutation.operationId) != 1) continue
            try {
                var response = executeMutationRequest(mutation)
                var status = response.code()

                if (status == 429) {
                    val retryAfterSec = response.headers()["Retry-After"]?.trim()?.toLongOrNull()
                    if (retryAfterSec != null && retryAfterSec in 1L..60L) {
                        var remainingMs = retryAfterSec * 1000L
                        while (remainingMs > 0L && !isStopped) {
                            val chunk = minOf(remainingMs, 1_000L)
                            kotlinx.coroutines.delay(chunk)
                            remainingMs -= chunk
                        }
                        if (!isStopped) {
                            response = executeMutationRequest(mutation)
                            status = response.code()
                        }
                    } else if (retryAfterSec != null && retryAfterSec > 60L) {
                        val nextAt = System.currentTimeMillis() + (retryAfterSec.coerceAtMost(6L * 60L * 60L) * 1000L)
                        dao.recordRetry(mutation.operationId, "HTTP 429", "HTTP_429", nextAt)
                        hasRetryableWork = true
                        continue
                    }
                }

                when {
                    response.isSuccessful || (mutation.operation == LocalFileMutation.DELETE && status == 404) -> {
                        dao.remove(mutation.operationId)
                        refreshFolder(repository, mutation)
                    }
                    status == 401 || status == 403 -> {
                        dao.pauseAccount(mutation.accountId, "Authentication required")
                        hasRetryableWork = true
                        return Result.retry()
                    }
                    status == 409 -> {
                        dao.markConflict(mutation.operationId, "The remote item conflicts with this change", "REMOTE_CONFLICT")
                        rollbackOptimisticProjection(database, mutation)
                        refreshFolder(repository, mutation)
                    }
                    isPermanentClientError(status) -> {
                        dao.markPermanent(mutation.operationId, "HTTP $status: ${response.errorBody()?.string()?.take(300).orEmpty()}")
                        rollbackOptimisticProjection(database, mutation)
                        refreshFolder(repository, mutation)
                    }
                    else -> {
                        val retryAt = if (status == 429) {
                            val sec = response.headers()["Retry-After"]?.trim()?.toLongOrNull()?.coerceIn(1L, 6L * 60L * 60L)
                            if (sec != null) System.currentTimeMillis() + sec * 1000L else nextAttemptAt(mutation.attemptCount)
                        } else {
                            nextAttemptAt(mutation.attemptCount)
                        }
                        dao.recordRetry(mutation.operationId, "HTTP $status", "HTTP_$status", retryAt)
                        hasRetryableWork = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                dao.recordRetry(mutation.operationId, e.message(), "NETWORK", nextAttemptAt(mutation.attemptCount))
                hasRetryableWork = true
            } catch (e: IOException) {
                dao.recordRetry(mutation.operationId, e.message ?: "Network error", "NETWORK", nextAttemptAt(mutation.attemptCount))
                hasRetryableWork = true
            } catch (e: UnsupportedOperationMutationException) {
                dao.markPermanent(mutation.operationId, "Unsupported queued operation", "UNSUPPORTED_OPERATION")
                rollbackOptimisticProjection(database, mutation)
            } catch (e: Exception) {
                dao.markPermanent(mutation.operationId, e.message ?: "Invalid mutation", "INVALID_MUTATION")
                rollbackOptimisticProjection(database, mutation)
            }
        }
        return if (hasRetryableWork) Result.retry() else Result.success()
    }

    private class UnsupportedOperationMutationException : IllegalStateException("Unsupported queued operation")

    private suspend fun executeMutationRequest(mutation: LocalFileMutation): retrofit2.Response<*> {
        return when (mutation.operation) {
            LocalFileMutation.RENAME -> ApiClient.apiService.renameFile(
                mutation.remoteFileId ?: mutation.fileId ?: throw IllegalArgumentException("Missing remote file id"),
                mutation.accountId,
                RenameRequest(mutation.name ?: throw IllegalArgumentException("Missing name"))
            )
            LocalFileMutation.DELETE -> ApiClient.apiService.deleteFile(
                mutation.remoteFileId ?: mutation.fileId ?: throw IllegalArgumentException("Missing remote file id"),
                mutation.accountId
            )
            LocalFileMutation.CREATE_FOLDER -> ApiClient.apiService.createFolder(
                mutation.accountId,
                FolderRequest(
                    mutation.name ?: throw IllegalArgumentException("Missing folder name"),
                    mutation.parentFolderId.takeUnless { it == "root" }
                )
            )
            else -> throw UnsupportedOperationMutationException()
        }
    }

    private suspend fun refreshFolder(repository: CloudFileRepository, mutation: LocalFileMutation) {
        repository.invalidateFolderCache(mutation.accountId, mutation.parentFolderId)
        repository.invalidateStorageCache()
        repository.refresh(mutation.accountId, mutation.parentFolderId, force = true)
    }

    private suspend fun rollbackOptimisticProjection(database: AppDatabase, mutation: LocalFileMutation) {
        if (mutation.operation == LocalFileMutation.CREATE_FOLDER) {
            mutation.optimisticFileId?.let { database.cloudFileDao().deleteById(it) }
        }
    }

    private fun nextAttemptAt(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, 8)
        val base = (30_000L * (1L shl exponent)).coerceAtMost(6 * 60 * 60 * 1000L)
        return System.currentTimeMillis() + base + Random.nextLong(0L, 10_000L)
    }

    private fun isPermanentClientError(status: Int): Boolean =
        status in 400..499 && status !in setOf(401, 403, 404, 408, 409, 429)

    companion object {
        private const val UNIQUE_WORK_NAME = "cloudyte_file_mutation_outbox"
        private const val TAG = "FileMutationWorker"

        @Volatile private var appContext: Context? = null

        fun initialize(context: Context) { appContext = context.applicationContext }

        fun enqueue() {
            val context = appContext ?: return
            try {
                val request = OneTimeWorkRequestBuilder<FileMutationWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not schedule file-mutation replay; entries remain in Room", e)
            }
        }
    }
}
