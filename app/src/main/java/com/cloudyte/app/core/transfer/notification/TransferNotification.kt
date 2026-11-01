package com.cloudyte.app.core.transfer.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.cloudyte.app.R

object TransferNotification {
    const val CHANNEL_ID = "cloudyte_transfers"
    const val CHANNEL_NAME = "Cloudyte transfers"
    const val NOTIFICATION_ID = 2001

    fun foregroundInfo(context: Context, transferred: Long, total: Long, currentFile: String? = null): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Upload and download progress"
                }
            )
        }
        val title = currentFile?.let { "Transferring $it" } ?: "Cloudyte transfers"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if (total > 0L) "${transferred.coerceAtLeast(0L)} of $total bytes" else "Working through transfer queue")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (total > 0L) {
                    val progress = ((transferred.coerceAtLeast(0L).toDouble() / total.toDouble()) * 1000).toInt().coerceIn(0, 1000)
                    setProgress(1000, progress, false)
                } else setProgress(0, 0, true)
            }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
