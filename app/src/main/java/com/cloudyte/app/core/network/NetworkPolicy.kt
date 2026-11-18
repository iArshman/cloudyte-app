package com.cloudyte.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.cloudyte.app.data.UserPreferencesState
import com.cloudyte.app.db.LocalTransfer
import kotlinx.coroutines.flow.Flow

/** Provider-independent connectivity state used by workers and scheduling decisions. */
enum class NetworkStatus {
    UNAVAILABLE,
    VALIDATED_METERED,
    VALIDATED_UNMETERED,
    CAPTIVE_PORTAL
}

fun NetworkStatus.isOnline(): Boolean =
    this == NetworkStatus.VALIDATED_METERED || this == NetworkStatus.VALIDATED_UNMETERED

data class NetworkPolicySnapshot(
    val status: NetworkStatus,
    val allowMeteredTransfers: Boolean,
    val allowRoaming: Boolean,
    val requireChargingForBackup: Boolean,
    val batterySaverActive: Boolean,
    val isRoaming: Boolean = false,
    val isCharging: Boolean = false,
    val batteryNotLow: Boolean = true,
    val requireWifiForBackup: Boolean = !allowMeteredTransfers
)

interface NetworkMonitor {
    val status: Flow<NetworkStatus>
    fun current(): NetworkStatus
    fun refresh(): NetworkStatus = current()
    fun snapshot(preferences: UserPreferencesState): NetworkPolicySnapshot
}

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class WaitingForNetwork(val reason: String) : PolicyDecision
    data class WaitingForCharging(val reason: String) : PolicyDecision
    data class WaitingForBattery(val reason: String) : PolicyDecision
    data class WaitingForRoaming(val reason: String) : PolicyDecision
    data class WaitingForAuth(val reason: String) : PolicyDecision
}

/** Central policy: browsing remains cache-first, while durable transfers are gated here. */
interface NetworkPolicy {
    fun canStart(transfer: LocalTransfer, snapshot: NetworkPolicySnapshot): PolicyDecision
}

class DefaultNetworkPolicy : NetworkPolicy {
    override fun canStart(transfer: LocalTransfer, snapshot: NetworkPolicySnapshot): PolicyDecision {
        if (snapshot.status == NetworkStatus.UNAVAILABLE || snapshot.status == NetworkStatus.CAPTIVE_PORTAL) {
            return PolicyDecision.WaitingForNetwork("NETWORK_UNAVAILABLE")
        }
        if (snapshot.isRoaming && !snapshot.allowRoaming) {
            return PolicyDecision.WaitingForRoaming("ROAMING_DISABLED")
        }
        val isManualBackup = transfer.operationType.equals("MANUAL_BACKUP", ignoreCase = true)
        if (isManualBackup) {
            return PolicyDecision.Allowed
        }
        val isBackup = transfer.operationType.equals("BACKUP", ignoreCase = true) ||
            transfer.direction.equals("BACKUP", ignoreCase = true)
        if (isBackup && snapshot.requireChargingForBackup && !snapshot.isCharging) {
            return PolicyDecision.WaitingForCharging("CHARGING_REQUIRED")
        }
        if (isBackup && snapshot.batterySaverActive) {
            return PolicyDecision.WaitingForBattery("BATTERY_SAVER_ACTIVE")
        }
        if (isBackup && !snapshot.batteryNotLow) {
            return PolicyDecision.WaitingForBattery("BATTERY_LOW")
        }
        if (!isBackup && snapshot.status == NetworkStatus.VALIDATED_METERED && !snapshot.allowMeteredTransfers) {
            return PolicyDecision.WaitingForNetwork("METERED_NETWORK_DISABLED")
        }
        if (isBackup && snapshot.status == NetworkStatus.VALIDATED_METERED && snapshot.requireWifiForBackup) {
            return PolicyDecision.WaitingForNetwork("BACKUP_WIFI_ONLY")
        }
        return PolicyDecision.Allowed
    }
}

class ConnectivityNetworkMonitor @JvmOverloads constructor(
    private val context: Context,
    private val powerMonitor: PowerMonitor? = null,
    private val onRouteChanged: () -> Unit = {}
) : NetworkMonitor {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastNetworkHandle = java.util.concurrent.atomic.AtomicLong(-1L)
    private val _status = kotlinx.coroutines.flow.MutableStateFlow(readStatus())
    override val status: Flow<NetworkStatus> = _status

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            val handle = runCatching { network.networkHandle }.getOrDefault(-1L)
            val prev = lastNetworkHandle.getAndSet(handle)
            if (prev != -1L && handle != -1L && prev != handle) {
                runCatching { onRouteChanged() }
            }
            publishForNetwork(network)
        }

        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
            val handle = runCatching { network.networkHandle }.getOrDefault(-1L)
            if (handle != -1L) {
                lastNetworkHandle.compareAndSet(-1L, handle)
            }
            val next = classifyCapabilities(capabilities)
            val prev = swapStatus(next)
            if (!prev.isOnline() && next.isOnline()) {
                runCatching { onRouteChanged() }
            }
        }

        override fun onLost(network: android.net.Network) {
            val handle = runCatching { network.networkHandle }.getOrDefault(-1L)
            if (handle != -1L) {
                lastNetworkHandle.compareAndSet(handle, -1L)
            }
            refresh()
        }

        override fun onBlockedStatusChanged(network: android.net.Network, blocked: Boolean) {
            if (blocked) {
                _status.value = NetworkStatus.UNAVAILABLE
            } else {
                publishForNetwork(network)
            }
        }
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
            runCatching { powerMonitor?.start() }
        }
        refresh()
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
            runCatching { powerMonitor?.stop() }
        }
    }

    override fun current(): NetworkStatus = if (started.get()) _status.value else readStatus()

    override fun refresh(): NetworkStatus {
        val activeNetwork = runCatching { connectivity?.activeNetwork }.getOrNull()
        val handle = activeNetwork?.let { runCatching { it.networkHandle }.getOrDefault(-1L) } ?: -1L
        val prevHandle = lastNetworkHandle.getAndSet(handle)
        val caps = activeNetwork?.let { runCatching { connectivity?.getNetworkCapabilities(it) }.getOrNull() }
        val next = classifyCapabilities(caps)
        val prevStatus = swapStatus(next)
        if ((!prevStatus.isOnline() && next.isOnline()) ||
            (prevHandle != -1L && handle != -1L && prevHandle != handle)
        ) {
            runCatching { onRouteChanged() }
        }
        return next
    }

    override fun snapshot(preferences: UserPreferencesState): NetworkPolicySnapshot {
        val capabilities = runCatching {
            connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()
        val powerSnap = powerMonitor?.currentSnapshot() ?: run {
            val power = context.getSystemService(PowerManager::class.java)
            val battery = context.getSystemService(BatteryManager::class.java)
            val batteryLevel = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            PowerSnapshot(
                isCharging = battery?.isCharging == true,
                batteryLevel = batteryLevel,
                batterySaverActive = power?.isPowerSaveMode == true
            )
        }
        return NetworkPolicySnapshot(
            status = classifyCapabilities(capabilities),
            allowMeteredTransfers = preferences.allowMeteredTransfers,
            allowRoaming = preferences.allowRoaming,
            requireChargingForBackup = preferences.backupChargingOnly,
            batterySaverActive = powerSnap.batterySaverActive,
            isRoaming = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false,
            isCharging = powerSnap.isCharging,
            batteryNotLow = powerSnap.batteryNotLow,
            requireWifiForBackup = preferences.backupWifiOnly
        )
    }

    private fun swapStatus(next: NetworkStatus): NetworkStatus {
        while (true) {
            val prev = _status.value
            if (_status.compareAndSet(prev, next)) return prev
        }
    }

    private fun publishForNetwork(network: android.net.Network) {
        val active = runCatching { connectivity?.activeNetwork }.getOrNull() ?: network
        val caps = runCatching { connectivity?.getNetworkCapabilities(active) }.getOrNull()
        val next = classifyCapabilities(caps)
        val prev = swapStatus(next)
        if (!prev.isOnline() && next.isOnline()) {
            runCatching { onRouteChanged() }
        }
    }

    private fun readStatus(): NetworkStatus = classifyCapabilities(
        runCatching {
            connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()
    )

    internal fun classifyCapabilities(capabilities: NetworkCapabilities?): NetworkStatus {
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkStatus.UNAVAILABLE
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return NetworkStatus.CAPTIVE_PORTAL
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return NetworkStatus.UNAVAILABLE
        }
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkStatus.VALIDATED_UNMETERED
        } else {
            NetworkStatus.VALIDATED_METERED
        }
    }
}

class TransferNetworkGate(
    private val monitor: NetworkMonitor,
    private val policy: NetworkPolicy,
    private val preferences: suspend () -> UserPreferencesState
) {
    suspend fun check(transfer: LocalTransfer): PolicyDecision =
        policy.canStart(transfer, monitor.snapshot(preferences()))
}
