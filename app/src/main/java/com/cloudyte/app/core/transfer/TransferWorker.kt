package com.cloudyte.app.core.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.core.transfer.notification.TransferNotification

class TransferWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? CloudyteApplication
            ?: return Result.failure()
        if (inputData.getBoolean(KEY_QUEUE_DRAIN, false)) return drainQueue(application)
        val transferId = inputData.getString(KEY_TRANSFER_ID)
            ?: return Result.failure()
        val command = when (inputData.getString(KEY_COMMAND)) {
            COMMAND_START -> TransferCommand.Start(transferId)
            COMMAND_RETRY -> TransferCommand.Retry(transferId)
            COMMAND_PAUSE -> TransferCommand.Pause(transferId)
            COMMAND_RESUME -> TransferCommand.Resume(transferId)
            COMMAND_CANCEL -> TransferCommand.Cancel(transferId)
            else -> return Result.failure()
        }

        return try {
            application.container.transferCoordinator.dispatch(command)
            Result.success()
        } catch (error: Throwable) {
            // Scheduling can fail transiently during process recreation or network recovery.
            // Let WorkManager apply its configured backoff instead of losing the command.
            Result.retry()
        }
    }

    private suspend fun drainQueue(application: CloudyteApplication): Result {
        val container = application.container
        if (!container.transferRolloutGate.isEnabled()) {
            val rollout = container.transferRolloutGate.current()
            container.transferDiagnostics.recordEvent(
                operationType = "rollout_gate",
                state = "DISABLED",
                errorCode = "ROLLOUT_DISABLED",
                networkState = rollout.reason,
                workerRunId = id.toString()
            )
            return Result.success()
        }
        val now = System.currentTimeMillis()
        container.database.transferQueueDao().recoverExpiredLeases(now)
        container.database.transferQueueDao().requeueNetworkWaiters(now)
        setForeground(
            TransferNotification.foregroundInfo(
                applicationContext,
                container.database.transferQueueDao().aggregateBytesTransferred(),
                container.database.transferQueueDao().aggregateTotalBytes()
            )
        )
        val trigger = runCatching { DrainTrigger.valueOf(inputData.getString(KEY_TRIGGER) ?: "APP_START") }
            .getOrDefault(DrainTrigger.APP_START)
        val drained = container.transferQueueDrainer.drain(trigger, { isStopped }) { snapshot ->
            setProgress(
                Data.Builder()
                    .putString("transfer_id", snapshot.transferId)
                    .putString("transfer_state", snapshot.state.name)
                    .putLong("bytes_transferred", snapshot.bytesTransferred)
                    .putLong("total_bytes", snapshot.totalBytes)
                    .putLong("bytes_per_second", snapshot.bytesPerSecond)
                    .putLong("eta_seconds", snapshot.etaSeconds ?: -1L)
                    .build()
            )
            setForeground(
                TransferNotification.foregroundInfo(
                    applicationContext,
                    container.database.transferQueueDao().aggregateBytesTransferred(),
                    container.database.transferQueueDao().aggregateTotalBytes(),
                    snapshot.transferId
                )
            )
        }
        val retryAt = container.database.transferQueueDao().nextRetryAt(System.currentTimeMillis())
        if (retryAt != null) container.transferScheduler.scheduleRetryAt(retryAt)
        val leaseExpiry = container.database.transferQueueDao().nextLeaseExpiry(System.currentTimeMillis())
        if (leaseExpiry != null) container.transferScheduler.scheduleLeaseRecoveryAt(leaseExpiry)
        if (container.database.transferQueueDao().hasNetworkWaiters()) container.transferScheduler.scheduleNetworkRecovery()
        return when (drained) {
            is DrainResult.Drained -> if (drained.hasMore) Result.retry() else Result.success()
            DrainResult.Stopped -> Result.retry()
        }
    }

    companion object {
        const val KEY_QUEUE_DRAIN = "queue_drain"
        const val KEY_TRIGGER = "queue_trigger"
        const val KEY_TRANSFER_ID = "transfer_id"
        const val KEY_COMMAND = "transfer_command"
        const val COMMAND_START = "start"
        const val COMMAND_RETRY = "retry"
        const val COMMAND_PAUSE = "pause"
        const val COMMAND_RESUME = "resume"
        const val COMMAND_CANCEL = "cancel"
    }
}
