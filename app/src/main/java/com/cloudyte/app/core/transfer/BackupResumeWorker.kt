package com.cloudyte.app.core.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.data.UserPreferences
import kotlinx.coroutines.flow.first

/**
 * Constraint-triggered worker scheduled when auto-backups are held waiting for
 * charging or unmetered Wi-Fi. Requeues waiting backup transfers and kicks the
 * queue drainer immediately once OS WorkManager constraints are satisfied.
 */
class BackupResumeWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? CloudyteApplication ?: return Result.success()
        val container = application.container
        val queueDao = container.database.transferQueueDao()
        val now = System.currentTimeMillis()
        queueDao.requeueNetworkWaiters(now)
        if (queueDao.hasRunnableTransfers(now)) {
            val prefs = runCatching { UserPreferences.observe(applicationContext).first() }.getOrNull()
            val allowMetered = !(prefs?.backupWifiOnly ?: true)
            container.transferScheduler.kick(ScheduleReason.NETWORK_RESTORED, allowMetered = allowMetered)
            container.kickImmediateDrain(DrainTrigger.NETWORK_RESTORED, "backup_constraint_resume")
        }
        return Result.success()
    }
}
