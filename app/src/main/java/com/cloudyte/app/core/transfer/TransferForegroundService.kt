package com.cloudyte.app.core.transfer

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.core.transfer.notification.NotificationHelper
import com.cloudyte.app.core.transfer.notification.TransferNotification
import com.cloudyte.app.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the Cloudyte process promoted as an active `dataSync` foreground service while
 * in-process uploads or downloads are running so Android and OEM background network policies
 * do not sever live TCP sockets with ECONNABORTED when the user leaves the app.
 */
class TransferForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    @Volatile
    private var promoted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        promoteInitialForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoted && !promoteInitialForeground()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val app = applicationContext as? CloudyteApplication
        if (app == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
            ?.let { raw -> runCatching { DrainTrigger.valueOf(raw) }.getOrNull() }
            ?: DrainTrigger.USER_ENQUEUE

        // Ensure drain is running if service was started directly or restarted by the system
        if (!app.container.isImmediateDrainActive.value) {
            app.container.kickImmediateDrain(
                trigger = trigger,
                reason = "foreground_service",
                startForegroundService = false
            )
        }

        ensureMonitorRunning(app)
        return START_NOT_STICKY
    }

    private fun promoteInitialForeground(): Boolean {
        val notification = TransferNotification.buildNotification(
            context = this,
            transferred = 0L,
            total = 0L,
            currentFile = null,
            showDetailedProgress = false
        )
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, TransferNotification.NOTIFICATION_ID, notification, type)
            promoted = true
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to promote transfer foreground service: ${error.javaClass.simpleName}")
            promoted = false
            false
        }
    }

    private fun ensureMonitorRunning(app: CloudyteApplication) {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            val queueDao = app.container.database.transferQueueDao()
            val manager = NotificationManagerCompat.from(this@TransferForegroundService)
            var lastTransferred = -1L
            var lastTotal = -1L
            var lastFile: String? = null
            var lastDetailed: Boolean? = null
            var lastNotifyAt = 0L

            // Initial grace window so newly dispatched queue writes settle before checking exit
            delay(300L)

            while (isActive) {
                val draining = app.container.isImmediateDrainActive.value
                val hasLive = runCatching { queueDao.hasLiveTransfers() }.getOrDefault(false)
                if (!draining && !hasLive) {
                    delay(450L)
                    val stillDraining = app.container.isImmediateDrainActive.value
                    val stillHasLive = runCatching { queueDao.hasLiveTransfers() }.getOrDefault(false)
                    if (!stillDraining && !stillHasLive) {
                        break
                    }
                }

                val prefs = runCatching {
                    UserPreferences.observe(this@TransferForegroundService).first()
                }.getOrNull()
                val showDetailed = prefs?.let { it.notificationsEnabled && it.progressAlerts } ?: false
                val totals = runCatching { queueDao.aggregateProgressTotals() }.getOrNull()
                val transferred = totals?.bytesTransferred ?: 0L
                val total = totals?.totalBytes ?: 0L
                val activeFile = runCatching { queueDao.currentActiveFileName() }.getOrNull()
                    ?: app.container.liveTransferSnapshot.value?.transferId?.let { activeId ->
                        runCatching { queueDao.currentRow(activeId)?.fileName }.getOrNull()
                    }

                val now = System.currentTimeMillis()
                val changed = transferred != lastTransferred ||
                    total != lastTotal ||
                    activeFile != lastFile ||
                    showDetailed != lastDetailed

                if (changed && (lastNotifyAt == 0L || (now - lastNotifyAt) >= 450L)) {
                    lastTransferred = transferred
                    lastTotal = total
                    lastFile = activeFile
                    lastDetailed = showDetailed
                    lastNotifyAt = now

                    val notification = TransferNotification.buildNotification(
                        context = this@TransferForegroundService,
                        transferred = transferred,
                        total = total,
                        currentFile = activeFile,
                        showDetailedProgress = showDetailed
                    )
                    if (NotificationHelper.hasSystemPermissionAndEnabled(this@TransferForegroundService)) {
                        runCatching { manager.notify(TransferNotification.NOTIFICATION_ID, notification) }
                    }
                }

                delay(400L)
            }

            ServiceCompat.stopForeground(this@TransferForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            promoted = false
            stopSelf()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync foreground service timeout reached; handing remaining work to WorkManager")
        val app = applicationContext as? CloudyteApplication
        app?.container?.transferScheduler?.kick(ScheduleReason.APP_START, allowMetered = true)
        monitorJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        promoted = false
        stopSelf(startId)
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        if (promoted) {
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            promoted = false
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TransferFgService"
        private const val EXTRA_TRIGGER = "extra_drain_trigger"

        fun isAppInForeground(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val myPid = android.os.Process.myPid()
            val appProc = am.runningAppProcesses?.firstOrNull { it.pid == myPid } ?: return false
            return appProc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        }

        fun startIfAllowed(context: Context, trigger: DrainTrigger): Boolean {
            val appContext = context.applicationContext
            val isInteractive = trigger == DrainTrigger.USER_ENQUEUE ||
                trigger == DrainTrigger.USER_RETRY ||
                trigger == DrainTrigger.USER_REQUEST
            if (!isInteractive && !isAppInForeground(appContext)) {
                return false
            }
            val intent = Intent(appContext, TransferForegroundService::class.java).apply {
                putExtra(EXTRA_TRIGGER, trigger.name)
            }
            return try {
                ContextCompat.startForegroundService(appContext, intent)
                true
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Foreground service start not allowed in current app state: ${error.javaClass.simpleName}")
                false
            } catch (error: SecurityException) {
                Log.w(TAG, "Foreground service start denied by security policy: ${error.javaClass.simpleName}")
                false
            }
        }
    }
}
