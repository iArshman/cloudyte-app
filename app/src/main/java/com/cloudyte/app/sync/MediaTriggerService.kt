package com.cloudyte.app.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.cloudyte.app.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Direct OS JobScheduler trigger using fixed JOB_ID for zero-permission instant
 * MediaStore photo/video notifications, eliminating WorkManager trigger job churn.
 */
object MediaTriggerScheduler {
    private const val TAG = "MediaTriggerScheduler"
    const val MEDIA_TRIGGER_JOB_ID = 4102

    fun schedule(
        context: Context,
        wifiOnly: Boolean,
        chargingOnly: Boolean
    ): Boolean {
        val appContext = context.applicationContext
        val jobScheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return false
        return runCatching {
            val networkType = if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            val builder = JobInfo.Builder(
                MEDIA_TRIGGER_JOB_ID,
                ComponentName(appContext, MediaTriggerService::class.java)
            )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(2_000L)
                .setTriggerContentMaxDelay(15_000L)
                .setRequiredNetworkType(networkType)
                .setRequiresCharging(chargingOnly)
            jobScheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
        }.onFailure { err ->
            Log.w(TAG, "Failed to schedule MediaTriggerService: ${err.message}")
        }.getOrDefault(false)
    }

    fun isScheduled(context: Context): Boolean {
        val jobScheduler = context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return false
        return jobScheduler.allPendingJobs.any { it.id == MEDIA_TRIGGER_JOB_ID }
    }

    fun cancel(context: Context) {
        val jobScheduler = context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return
        runCatching { jobScheduler.cancel(MEDIA_TRIGGER_JOB_ID) }
    }
}

class MediaTriggerService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                val prefs = runCatching { UserPreferences.observe(appContext).first() }.getOrNull()
                if (prefs != null && prefs.autoBackupEnabled && prefs.quickMediaBackupEnabled) {
                    AutoSyncManager.enqueueImmediateSync(
                        context = appContext,
                        prefsOverride = prefs,
                        forceReupload = false,
                        isManual = false
                    )
                    MediaTriggerScheduler.schedule(
                        context = appContext,
                        wifiOnly = prefs.backupWifiOnly,
                        chargingOnly = prefs.backupChargingOnly
                    )
                }
            } catch (err: Exception) {
                Log.w("MediaTriggerService", "Media trigger execution failed: ${err.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        return false
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
