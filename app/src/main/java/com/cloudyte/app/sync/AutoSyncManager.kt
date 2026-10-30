package com.cloudyte.app.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cloudyte.app.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object AutoSyncManager {
    private const val TAG = "AutoSyncManager"
    const val UNIQUE_PERIODIC_WORK = "AutoBackup"
    const val UNIQUE_IMMEDIATE_WORK = "ImmediateAutoBackup"
    const val UNIQUE_MANUAL_WORK = "ManualSyncNow"
    private var foregroundWatcherJob: Job? = null

    fun init(context: Context, scope: CoroutineScope) {
        SyncLogger.init(context)
        scope.launch(Dispatchers.IO) {
            val isEnabled = SyncPreferences.getAutoBackupEnabled(context).first()
            if (isEnabled) {
                scheduleAutoBackup(context, ExistingPeriodicWorkPolicy.KEEP)
                triggerImmediateSync(context, forceReupload = false, isManual = false)
            }
        }
        startForegroundWatcher(context, scope)
    }

    fun setAutoBackup(context: Context, enabled: Boolean) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            SyncPreferences.setAutoBackupEnabled(context, enabled)
            if (enabled) {
                scheduleAutoBackup(context, ExistingPeriodicWorkPolicy.UPDATE)
                SyncLogger.log("Auto-backup enabled: Starting immediate initial sync check...", SyncLogLevel.INFO)
                triggerImmediateSync(context, forceReupload = false, isManual = false)
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK)
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_IMMEDIATE_WORK)
                SyncLogger.log("Auto-backup disabled.", SyncLogLevel.INFO)
            }
        }
    }

    suspend fun buildConstraintsFromPreferences(context: Context): Constraints {
        val prefs = UserPreferences.observe(context).first()
        val networkType = if (prefs.backupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(prefs.backupChargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    fun scheduleAutoBackup(
        context: Context,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE
    ) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val prefs = UserPreferences.observe(context).first()
            val constraints = buildConstraintsFromPreferences(context)
            val intervalHours = prefs.backupIntervalHours.toLong().coerceAtLeast(1L)

            val periodicRequest = PeriodicWorkRequestBuilder<BackupWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                policy,
                periodicRequest
            )
        }
    }

    fun triggerImmediateSync(context: Context, forceReupload: Boolean = false, isManual: Boolean = false) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val constraints = if (isManual) {
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            } else {
                buildConstraintsFromPreferences(context)
            }

            val data = Data.Builder()
                .putBoolean("is_manual", isManual)
                .putBoolean("force_reupload", forceReupload)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            val workName = if (isManual) UNIQUE_MANUAL_WORK else UNIQUE_IMMEDIATE_WORK
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                if (isManual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    fun onFolderOrAccountChanged(context: Context) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val isEnabled = SyncPreferences.getAutoBackupEnabled(context).first()
            if (isEnabled) {
                scheduleAutoBackup(context, ExistingPeriodicWorkPolicy.UPDATE)
                SyncLogger.log("Sync configuration updated: Triggering auto-sync...", SyncLogLevel.INFO)
                triggerImmediateSync(context, forceReupload = false, isManual = false)
            }
        }
    }

    private fun startForegroundWatcher(context: Context, scope: CoroutineScope) {
        foregroundWatcherJob?.cancel()
        foregroundWatcherJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(45_000)
                try {
                    val isEnabled = SyncPreferences.getAutoBackupEnabled(context).first()
                    val uri = SyncPreferences.getBackupUri(context).first()
                    val accountId = SyncPreferences.getBackupAccountId(context).first()

                    if (isEnabled && !uri.isNullOrBlank() && !accountId.isNullOrBlank()) {
                        triggerImmediateSync(context, forceReupload = false, isManual = false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Foreground watcher check error: ${e.message}")
                }
            }
        }
    }
}
