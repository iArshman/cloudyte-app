package com.example.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
    private const val UNIQUE_PERIODIC_WORK = "AutoBackup"
    private const val UNIQUE_IMMEDIATE_WORK = "ImmediateAutoBackup"
    private var foregroundWatcherJob: Job? = null

    fun init(context: Context, scope: CoroutineScope) {
        SyncLogger.init(context)
        scope.launch(Dispatchers.IO) {
            val isEnabled = SyncPreferences.getAutoBackupEnabled(context).first()
            if (isEnabled) {
                scheduleAutoBackup(context)
                // Trigger immediate background sync check on app launch
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
                scheduleAutoBackup(context)
                SyncLogger.log("Auto-backup enabled: Starting immediate initial sync check...", SyncLogLevel.INFO)
                triggerImmediateSync(context, forceReupload = false, isManual = false)
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK)
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_IMMEDIATE_WORK)
                SyncLogger.log("Auto-backup disabled.", SyncLogLevel.INFO)
            }
        }
    }

    fun scheduleAutoBackup(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    fun triggerImmediateSync(context: Context, forceReupload: Boolean = false, isManual: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = Data.Builder()
            .putBoolean("is_manual", isManual)
            .putBoolean("force_reupload", forceReupload)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .build()

        val workName = if (isManual) "ManualSyncNow" else UNIQUE_IMMEDIATE_WORK
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun onFolderOrAccountChanged(context: Context) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val isEnabled = SyncPreferences.getAutoBackupEnabled(context).first()
            if (isEnabled) {
                SyncLogger.log("Sync configuration updated: Triggering auto-sync...", SyncLogLevel.INFO)
                triggerImmediateSync(context, forceReupload = false, isManual = false)
            }
        }
    }

    private fun startForegroundWatcher(context: Context, scope: CoroutineScope) {
        foregroundWatcherJob?.cancel()
        foregroundWatcherJob = scope.launch(Dispatchers.IO) {
            // Check every 45 seconds while app is active
            while (isActive) {
                delay(45_000)
                try {
                    val isEnabled = SyncPreferences.getAutoBackupEnabled(context).first()
                    val uri = SyncPreferences.getBackupUri(context).first()
                    val accountId = SyncPreferences.getBackupAccountId(context).first()

                    if (isEnabled && !uri.isNullOrBlank() && !accountId.isNullOrBlank()) {
                        // Background auto-scan
                        triggerImmediateSync(context, forceReupload = false, isManual = false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Foreground watcher check error: ${e.message}")
                }
            }
        }
    }
}
