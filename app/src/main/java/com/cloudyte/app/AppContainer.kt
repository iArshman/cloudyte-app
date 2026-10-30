package com.cloudyte.app

import android.content.Context
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.core.common.DefaultDispatcherProvider
import com.cloudyte.app.core.common.DispatcherProvider
import com.cloudyte.app.data.CloudFileRepository
import com.cloudyte.app.data.TransferRepository
import com.cloudyte.app.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Composition root for application-scoped dependencies.
 *
 * Feature code can gradually depend on this container while the existing
 * package structure is migrated toward core/data/domain/features boundaries.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val dispatchers: DispatcherProvider = DefaultDispatcherProvider
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(appContext)
    }

    val cloudFileRepository: CloudFileRepository by lazy {
        CloudFileRepository(database, ApiClient.apiService)
    }

    val transferRepository: TransferRepository by lazy {
        TransferRepository(database.transferDao())
    }
}
