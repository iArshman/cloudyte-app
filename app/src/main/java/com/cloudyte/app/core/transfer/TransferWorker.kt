package com.cloudyte.app.core.transfer

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.core.transfer.notification.TransferNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class TransferWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val application = applicationContext as? CloudyteApplication
        val queueDao = application?.container?.database?.transferQueueDao()
        val totals = runCatching { queueDao?.aggregateProgressTotals() }.getOrNull()
        return TransferNotification.foregroundInfo(
            applicationContext,
            totals?.bytesTransferred ?: 0L,
            totals?.totalBytes ?: 0L
        )
    }

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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Scheduling can fail transiently during process recreation or network recovery.
            // Let WorkManager apply its configured backoff instead of losing the command.
            Result.retry()
        }
    }

    private suspend fun drainQueue(application: CloudyteApplication): Result {
        val container = application.container
        val trigger = runCatching { DrainTrigger.valueOf(inputData.getString(KEY_TRIGGER) ?: "APP_START") }
            .getOrDefault(DrainTrigger.APP_START)
        container.transferDiagnostics.recordEvent(
            operationType = "worker_start",
            state = trigger.name,
            workerRunId = id.toString()
        )
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
        val queueDao = container.database.transferQueueDao()
        val now = System.currentTimeMillis()
        queueDao.recoverExpiredLeases(now)
        queueDao.requeueNetworkWaiters(now)

        if (!queueDao.hasRunnableTransfers(now)) {
            scheduleFollowUpWork(container)
            container.transferDiagnostics.recordEvent(
                operationType = "worker_finish",
                state = "EMPTY",
                workerRunId = id.toString()
            )
            return Result.success()
        }

        val prefs = com.cloudyte.app.data.UserPreferences.observe(applicationContext).first()
        val showDetailedProgress = prefs.notificationsEnabled && prefs.progressAlerts
        val initialTotals = queueDao.aggregateProgressTotals()
        trySetForeground(
            TransferNotification.foregroundInfo(
                applicationContext,
                initialTotals.bytesTransferred,
                initialTotals.totalBytes,
                showDetailedProgress = showDetailedProgress
            )
        )
        var lastUiNotifyAt = 0L
        var lastTransferId: String? = null
        var totalProcessed = 0
        var lastResult: DrainResult = DrainResult.Drained(0, false)
        var batches = 0
        while (!isStopped && batches < MAX_WORKER_DRAIN_BATCHES) {
            val drained = container.transferQueueDrainer.drain(trigger, { isStopped }) { snapshot ->
                val currentMs = System.currentTimeMillis()
                val isTerminalSnapshot = snapshot.totalBytes > 0L && snapshot.bytesTransferred >= snapshot.totalBytes
                val transferChanged = snapshot.transferId != lastTransferId
                if (lastUiNotifyAt == 0L || transferChanged || isTerminalSnapshot || currentMs < lastUiNotifyAt || (currentMs - lastUiNotifyAt) >= 500L) {
                    lastUiNotifyAt = currentMs
                    lastTransferId = snapshot.transferId
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
                    val totals = queueDao.aggregateProgressTotals()
                    trySetForeground(
                        TransferNotification.foregroundInfo(
                            applicationContext,
                            totals.bytesTransferred,
                            totals.totalBytes,
                            snapshot.transferId,
                            showDetailedProgress = showDetailedProgress
                        )
                    )
                }
            }
            lastResult = drained
            batches++
            if (drained is DrainResult.Drained) {
                totalProcessed += drained.processed
                if (!drained.hasMore || drained.processed == 0) break
            } else {
                break
            }
        }
        scheduleFollowUpWork(container)
        val hasMoreRemaining = (lastResult as? DrainResult.Drained)?.hasMore == true
        container.transferDiagnostics.recordEvent(
            operationType = "worker_finish",
            state = if (lastResult == DrainResult.Stopped) "STOPPED" else "DONE_processed_$totalProcessed",
            workerRunId = id.toString()
        )
        return when {
            lastResult == DrainResult.Stopped || (hasMoreRemaining && batches >= MAX_WORKER_DRAIN_BATCHES) -> Result.retry()
            else -> Result.success()
        }
    }

    private suspend fun scheduleFollowUpWork(container: com.cloudyte.app.AppContainer) {
        val queueDao = container.database.transferQueueDao()
        val currentNow = System.currentTimeMillis()
        val retryAt = queueDao.nextRetryAt(currentNow)
        if (retryAt != null) container.transferScheduler.scheduleRetryAt(retryAt)
        val leaseExpiry = queueDao.nextLeaseExpiry(currentNow)
        if (leaseExpiry != null) container.transferScheduler.scheduleLeaseRecoveryAt(leaseExpiry)
        if (queueDao.hasNetworkWaiters()) container.transferScheduler.scheduleNetworkRecovery()
    }

    private suspend fun trySetForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w("TransferWorker", "Proceeding without foreground promotion: ${error.javaClass.simpleName}")
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
        private const val MAX_WORKER_DRAIN_BATCHES = 25
    }
}
