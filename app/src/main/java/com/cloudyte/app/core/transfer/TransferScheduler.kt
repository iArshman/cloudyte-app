package com.cloudyte.app.core.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class ActiveTransferSignal { PAUSE, CANCEL }

/**
 * Coordinates in-memory interruption signals between [TransferCoordinator], [TransferScheduler],
 * [RoomTransferQueueDrainer], and [CloudTransferOperationExecutor] so pausing or cancelling an
 * active transfer stops chunk streaming immediately without killing unrelated queued transfers.
 */
object ActiveTransferSignalRegistry {
    private val signals = ConcurrentHashMap<String, ActiveTransferSignal>()
    private val hooks = ConcurrentHashMap<String, (ActiveTransferSignal) -> Unit>()

    fun registerHook(transferId: String, onSignal: (ActiveTransferSignal) -> Unit) {
        hooks[transferId] = onSignal
        signals[transferId]?.let(onSignal)
    }

    fun unregisterHook(transferId: String) {
        hooks.remove(transferId)
    }

    fun signalPause(transferId: String) {
        signals[transferId] = ActiveTransferSignal.PAUSE
        hooks[transferId]?.invoke(ActiveTransferSignal.PAUSE)
    }

    fun signalCancel(transferId: String) {
        signals[transferId] = ActiveTransferSignal.CANCEL
        hooks[transferId]?.invoke(ActiveTransferSignal.CANCEL)
    }

    fun currentSignal(transferId: String): ActiveTransferSignal? = signals[transferId]

    fun clear(transferId: String) {
        signals.remove(transferId)
    }

    fun clearAll() {
        signals.clear()
        hooks.clear()
    }
}

interface TransferSchedulerContract {
    fun kick(reason: ScheduleReason, allowMetered: Boolean)
    fun retrySoon(reason: ScheduleReason)
    fun schedulePeriodicMaintenance()
    fun cancelQueuedTransfer(transferId: String)
    fun pauseActiveTransfer(transferId: String) {
        ActiveTransferSignalRegistry.signalPause(transferId)
    }
}

enum class ScheduleReason { USER_ENQUEUE, USER_RETRY, APP_START, NETWORK_RESTORED, PERIODIC_MAINTENANCE }

class TransferScheduler(private val context: Context) : TransferSchedulerContract {
    private val workManager by lazy {
        runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull()
    }

    override fun kick(reason: ScheduleReason, allowMetered: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(androidx.work.Data.Builder().putBoolean(TransferWorker.KEY_QUEUE_DRAIN, true).putString(TransferWorker.KEY_TRIGGER, reason.name).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager?.enqueueUniqueWork(UNIQUE_QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun retrySoon(reason: ScheduleReason) = kick(reason, allowMetered = false)

    fun scheduleRetryAt(timestampMillis: Long) {
        val delayMillis = (timestampMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(androidx.work.Data.Builder().putBoolean(TransferWorker.KEY_QUEUE_DRAIN, true).putString(TransferWorker.KEY_TRIGGER, ScheduleReason.USER_RETRY.name).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager?.enqueueUniqueWork(RETRY_DUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleLeaseRecoveryAt(timestampMillis: Long) {
        val delayMillis = (timestampMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(androidx.work.Data.Builder().putBoolean(TransferWorker.KEY_QUEUE_DRAIN, true).putString(TransferWorker.KEY_TRIGGER, ScheduleReason.APP_START.name).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager?.enqueueUniqueWork(LEASE_RECOVERY_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleNetworkRecovery() {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(androidx.work.Data.Builder().putBoolean(TransferWorker.KEY_QUEUE_DRAIN, true).putString(TransferWorker.KEY_TRIGGER, ScheduleReason.NETWORK_RESTORED.name).build())
            .setInitialDelay(60, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager?.enqueueUniqueWork(NETWORK_RECOVERY_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodicMaintenance() {
        val request = PeriodicWorkRequestBuilder<TransferWorker>(6, TimeUnit.HOURS)
            .setInputData(androidx.work.Data.Builder().putBoolean(TransferWorker.KEY_QUEUE_DRAIN, true).putString(TransferWorker.KEY_TRIGGER, ScheduleReason.PERIODIC_MAINTENANCE.name).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager?.enqueueUniquePeriodicWork(MAINTENANCE_WORK_NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelQueuedTransfer(transferId: String) {
        if (transferId.isBlank()) return
        ActiveTransferSignalRegistry.signalCancel(transferId)
        workManager?.cancelAllWorkByTag(transferWorkTag(transferId))
        workManager?.cancelUniqueWork("$UNIQUE_TRANSFER_COMMAND_PREFIX$transferId")
    }

    override fun pauseActiveTransfer(transferId: String) {
        if (transferId.isBlank()) return
        ActiveTransferSignalRegistry.signalPause(transferId)
    }

    companion object {
        const val UNIQUE_QUEUE_NAME = "cloudyte_transfer_queue"
        const val MAINTENANCE_WORK_NAME = "cloudyte_transfer_queue_maintenance"
        const val RETRY_DUE_WORK_NAME = "cloudyte_transfer_queue_retry_due"
        const val NETWORK_RECOVERY_WORK_NAME = "cloudyte_transfer_queue_network_recovery"
        const val LEASE_RECOVERY_WORK_NAME = "cloudyte_transfer_queue_lease_recovery"
        const val UNIQUE_TRANSFER_COMMAND_PREFIX = "cloudyte_transfer_cmd_"

        fun transferWorkTag(transferId: String): String = "cloudyte_transfer_id:$transferId"
    }
}

