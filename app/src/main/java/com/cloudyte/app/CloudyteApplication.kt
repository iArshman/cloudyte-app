package com.cloudyte.app

import android.app.Application
import android.util.Log
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.sync.AutoSyncManager
import com.cloudyte.app.sync.FileMutationWorker
import com.cloudyte.app.core.transfer.ScheduleReason
import com.cloudyte.app.core.transfer.WorkMaintenanceScheduler
import com.cloudyte.app.core.cache.CacheCleanupWorker

class CloudyteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        OfflineFileManager.init(this)
        container = AppContainer(this)
        FileMutationWorker.initialize(this)
        FileMutationWorker.enqueue()
        AutoSyncManager.init(this, container.applicationScope)
        try {
            container.transferScheduler.schedulePeriodicMaintenance()
            WorkMaintenanceScheduler.scheduleMaintenance(this)
            CacheCleanupWorker.schedule(this)
            container.transferScheduler.kick(ScheduleReason.APP_START, allowMetered = true)
        } catch (error: IllegalStateException) {
            Log.w("CloudyteApplication", "WorkManager is not initialized; startup queue work will be scheduled on the next trigger", error)
        }
    }
}
