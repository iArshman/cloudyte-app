package com.cloudyte.app.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import coil.imageLoader
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.FileRepository
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.core.cache.PreviewStore
import com.cloudyte.app.core.security.SecureFileCleanup
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.features.player.MediaStreamCache
import com.cloudyte.app.sync.AutoSyncManager
import com.cloudyte.app.sync.SyncLogger
import com.cloudyte.app.sync.SyncLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class LogoutResult(
    val remoteRevoked: Boolean,
    val warningMessage: String? = null
)

object AccountSessionHelper {
    suspend fun performLogout(
        context: Context,
        remoteLogoutCall: suspend () -> Boolean = {
            val response = withTimeoutOrNull(4_000L) {
                ApiClient.apiService.logout()
            }
            response?.isSuccessful == true
        }
    ): LogoutResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val remoteOk = try {
            remoteLogoutCall()
        } catch (_: Exception) {
            false
        }

        // Always complete local sign-out cleanly even if remote revocation fails/offline.
        ApiClient.saveToken(null)
        FileRepository.clear()
        runCatching { AutoSyncManager.cancelScheduledWork(appContext) }
        runCatching { WorkManager.getInstance(appContext).cancelAllWork() }

        val app = appContext as? CloudyteApplication
        runCatching { app?.container?.contentKeyManager?.clearInMemoryCache() }

        val db = AppDatabase.getDatabase(appContext)
        runCatching {
            db.withTransaction {
                db.cloudFileDao().clearAll()
                db.accountDao().clearAccounts()
                db.storageSummaryDao().clearAll()
                db.folderSyncMetaDao().clearAll()
                db.transferDao().clearAll()
                db.fileMutationDao().clearAll()
                db.fileTombstoneDao().clearAll()
                db.backupSourceDao().clearAll()
                db.exclusionDao().clearAll()
                db.uploadRecordDao().clearHistory()
                db.thumbnailDao().deleteAll()
            }
        }

        runCatching { OfflineFileManager.clearAllOfflineCache(appContext) }
        runCatching { (app?.container?.previewStore ?: PreviewStore(appContext)).clear() }
        runCatching { MediaStreamCache.clear(appContext) }
        runCatching { SecureFileCleanup.cleanupStaleTempFiles(appContext, emptySet()) }
        runCatching { SecureFileCleanup.clearShareAndTempDirs(appContext) }
        runCatching { File(appContext.filesDir, "cloudyte_thumbnails").deleteRecursively() }
        runCatching { ApiClient.okHttpClient.cache?.evictAll() }
        runCatching {
            appContext.imageLoader.memoryCache?.clear()
            appContext.imageLoader.diskCache?.clear()
        }

        runCatching { UserPreferences.setLastActiveAccountId(appContext, null) }
        runCatching { UserPreferences.setHomeStorageAccountId(appContext, null) }
        runCatching {
            appContext.getSharedPreferences("cloudyte_drive_state", Context.MODE_PRIVATE).edit().clear().apply()
        }
        runCatching {
            appContext.getSharedPreferences("cloudyte_audio_playback", Context.MODE_PRIVATE).edit().clear().apply()
        }
        runCatching { SyncLogger.clearLogs() }

        val warning = if (remoteOk) {
            null
        } else {
            "Signed out on this device, but the server session could not be revoked (offline or server error)."
        }
        SyncLogger.log(
            warning ?: "Signed out locally and revoked remote session.",
            if (remoteOk) SyncLogLevel.INFO else SyncLogLevel.WARNING
        )
        LogoutResult(remoteRevoked = remoteOk, warningMessage = warning)
    }
}
