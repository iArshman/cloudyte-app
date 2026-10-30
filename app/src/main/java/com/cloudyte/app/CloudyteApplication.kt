package com.cloudyte.app

import android.app.Application
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.sync.AutoSyncManager

class CloudyteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        OfflineFileManager.init(this)
        container = AppContainer(this)
        AutoSyncManager.init(this, container.applicationScope)
    }
}
