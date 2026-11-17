package com.cloudyte.app.sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cloudyte.app.core.transfer.notification.NotificationHelper
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.BackupSourceEntity
import com.cloudyte.app.db.BackupSourcePermissionStatus

enum class PermissionState {
    GRANTED,
    DENIED
}

data class AppPermissionSummary(
    val notificationsState: PermissionState,
    val runtimePostNotificationsGranted: Boolean,
    val osNotificationsEnabled: Boolean,
    val ignoringBatteryOptimizations: Boolean = false,
    val folderSources: List<BackupSourceEntity>,
    val healthyFolderCount: Int,
    val brokenFolderCount: Int
)

object PermissionsHelper {

    fun isRuntimePostNotificationsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        val runtimeOk = isRuntimePostNotificationsGranted(context)
        val managerOk = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(true)
        return runtimeOk && managerOk
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    suspend fun inspectAppPermissions(
        context: Context,
        db: AppDatabase = AppDatabase.getDatabase(context)
    ): AppPermissionSummary {
        val runtimeOk = isRuntimePostNotificationsGranted(context)
        val osEnabled = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(true)
        val notifState = if (runtimeOk && osEnabled) PermissionState.GRANTED else PermissionState.DENIED
        val batteryUnrestricted = isIgnoringBatteryOptimizations(context)

        val sources = BackupSourceManager.refreshAllSourceHealth(context, db)
        val healthy = sources.count { it.permissionStatus == BackupSourcePermissionStatus.GRANTED.name }
        val broken = sources.count { it.permissionStatus != BackupSourcePermissionStatus.GRANTED.name }

        return AppPermissionSummary(
            notificationsState = notifState,
            runtimePostNotificationsGranted = runtimeOk,
            osNotificationsEnabled = osEnabled,
            ignoringBatteryOptimizations = batteryUnrestricted,
            folderSources = sources,
            healthyFolderCount = healthy,
            brokenFolderCount = broken
        )
    }

    fun buildAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildNotificationSettingsIntent(context: Context): Intent {
        return NotificationHelper.buildAppNotificationSettingsIntent(context)
    }

    fun buildBatteryOptimizationSettingsIntent(context: Context): Intent {
        val batteryIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (batteryIntent.resolveActivity(context.packageManager) != null) {
            batteryIntent
        } else {
            buildAppDetailsSettingsIntent(context)
        }
    }
}
