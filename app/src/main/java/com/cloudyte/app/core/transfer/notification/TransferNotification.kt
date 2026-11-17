package com.cloudyte.app.core.transfer.notification

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.cloudyte.app.R
import com.cloudyte.app.data.formatStorageSize

object TransferNotification {
    const val CHANNEL_ID = "cloudyte_transfers"
    const val CHANNEL_NAME = "Cloudyte transfers"
    const val NOTIFICATION_ID = 2001

    fun buildNotification(
        context: Context,
        transferred: Long,
        total: Long,
        currentFile: String? = null,
        showDetailedProgress: Boolean = true
    ): Notification {
        NotificationHelper.ensureChannels(context)
        val title = if (showDetailedProgress && !currentFile.isNullOrBlank()) {
            "Transferring $currentFile"
        } else {
            "Cloudyte transfers"
        }
        val text = if (!showDetailedProgress) {
            "Transfer queue active"
        } else if (total > 0L) {
            "${formatStorageSize(transferred.coerceAtLeast(0L))} of ${formatStorageSize(total)}"
        } else {
            "Working through transfer queue"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (showDetailedProgress && total > 0L) {
                    val progress = ((transferred.coerceAtLeast(0L).toDouble() / total.toDouble()) * 1000).toInt().coerceIn(0, 1000)
                    setProgress(1000, progress, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    fun foregroundInfo(
        context: Context,
        transferred: Long,
        total: Long,
        currentFile: String? = null,
        showDetailedProgress: Boolean = true
    ): ForegroundInfo {
        val notification = buildNotification(
            context = context,
            transferred = transferred,
            total = total,
            currentFile = currentFile,
            showDetailedProgress = showDetailedProgress
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
