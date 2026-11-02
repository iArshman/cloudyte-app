package com.cloudyte.app.core.transfer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** WorkManager-only lifecycle scheduling; transfer execution remains in TransferWorker. */
object WorkMaintenanceScheduler {
    const val BOOT_RECOVERY_WORK = "cloudyte_boot_recovery"
    const val MAINTENANCE_WORK = "cloudyte_lifecycle_maintenance"

    fun scheduleBootRecovery(context: Context) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(androidx.work.Data.Builder()
                .putBoolean(TransferWorker.KEY_QUEUE_DRAIN, true)
                .putString(TransferWorker.KEY_TRIGGER, DrainTrigger.APP_START.name)
                .build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                BOOT_RECOVERY_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun scheduleMaintenance(context: Context) {
        val request = PeriodicWorkRequestBuilder<TransferWorker>(6, TimeUnit.HOURS)
            .setInputData(androidx.work.Data.Builder()
                .putBoolean(TransferWorker.KEY_QUEUE_DRAIN, true)
                .putString(TransferWorker.KEY_TRIGGER, DrainTrigger.PERIODIC_MAINTENANCE.name)
                .build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                MAINTENANCE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
