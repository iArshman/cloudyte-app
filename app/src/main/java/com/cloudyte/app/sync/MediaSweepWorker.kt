package com.cloudyte.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cloudyte.app.data.UserPreferences
import kotlinx.coroutines.flow.first

/**
 * Hourly fallback sweep worker for reliable media backup even when Doze mode or
 * vendor background restrictions defer instant JobScheduler/ContentObserver events.
 */
class MediaSweepWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = UserPreferences.observe(context).first()
        if (!prefs.autoBackupEnabled || !prefs.quickMediaBackupEnabled) {
            MediaTriggerScheduler.cancel(context)
            return Result.success()
        }
        MediaTriggerScheduler.cancel(context)
        val now = System.currentTimeMillis()
        if (prefs.lastBackupAt > 0L && (now - prefs.lastBackupAt) < 45L * 60_000L) {
            return Result.success()
        }
        AutoSyncManager.enqueueImmediateSync(
            context = context,
            prefsOverride = prefs,
            forceReupload = false,
            isManual = false
        )
        return Result.success()
    }
}
