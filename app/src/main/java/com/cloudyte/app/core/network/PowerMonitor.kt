package com.cloudyte.app.core.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class PowerSnapshot(
    val isCharging: Boolean = false,
    val batteryLevel: Int = 100,
    val batterySaverActive: Boolean = false
) {
    val batteryNotLow: Boolean
        get() = batteryLevel < 0 || batteryLevel >= 15
}

interface PowerMonitor {
    val snapshotFlow: StateFlow<PowerSnapshot>
    fun currentSnapshot(): PowerSnapshot
    fun start()
    fun stop()
}

/**
 * Reactive battery & power-saver monitor backed by OS broadcast events, avoiding per-transfer
 * Binder IPC polling against [BatteryManager] and [PowerManager].
 */
class AndroidPowerMonitor(context: Context) : PowerMonitor {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val started = AtomicBoolean(false)

    private val _snapshotFlow = MutableStateFlow(readInitialSnapshot())
    override val snapshotFlow: StateFlow<PowerSnapshot> = _snapshotFlow.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else _snapshotFlow.value.batteryLevel
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = plugged ||
                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    _snapshotFlow.value = _snapshotFlow.value.copy(
                        isCharging = charging,
                        batteryLevel = pct,
                        batterySaverActive = powerManager?.isPowerSaveMode == true
                    )
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    _snapshotFlow.value = _snapshotFlow.value.copy(
                        batterySaverActive = powerManager?.isPowerSaveMode == true
                    )
                }
            }
        }
    }

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.getOrNull()
        _snapshotFlow.value = readSnapshotFromIntent(sticky)
    }

    override fun stop() {
        if (started.compareAndSet(true, false)) {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    override fun currentSnapshot(): PowerSnapshot {
        return if (started.get()) _snapshotFlow.value else readInitialSnapshot()
    }

    private fun readInitialSnapshot(): PowerSnapshot {
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.getOrNull()
        return readSnapshotFromIntent(sticky)
    }

    private fun readSnapshotFromIntent(sticky: Intent?): PowerSnapshot {
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt()
        } else {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        }
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val charging = plugged || (batteryManager?.isCharging == true)
        val powerSave = powerManager?.isPowerSaveMode == true
        return PowerSnapshot(
            isCharging = charging,
            batteryLevel = pct,
            batterySaverActive = powerSave
        )
    }
}
