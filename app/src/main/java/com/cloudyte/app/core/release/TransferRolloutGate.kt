package com.cloudyte.app.core.release

import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * Local fail-safe gate for staged transfer rollout.
 *
 * The default is enabled so existing users keep current behavior. A remote-config
 * adapter or support action can disable the queue without deleting durable rows;
 * workers then leave rows untouched for rollback/recovery.
 */
class TransferRolloutGate(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val snapshot = AtomicReference(readSnapshot())

    fun isEnabled(): Boolean = snapshot.get().enabled

    fun disable(reason: String = "manual_rollback") {
        update(enabled = false, reason = reason)
    }

    fun enable(reason: String = "rollout_resumed") {
        update(enabled = true, reason = reason)
    }

    fun current(): Snapshot = snapshot.get()

    private fun update(enabled: Boolean, reason: String) {
        val next = Snapshot(
            enabled = enabled,
            reason = reason.take(MAX_REASON_LENGTH),
            updatedAt = System.currentTimeMillis()
        )
        preferences.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_REASON, next.reason)
            .putLong(KEY_UPDATED_AT, next.updatedAt)
            .apply()
        snapshot.set(next)
    }

    private fun readSnapshot(): Snapshot = Snapshot(
        enabled = preferences.getBoolean(KEY_ENABLED, true),
        reason = preferences.getString(KEY_REASON, "default_enabled") ?: "default_enabled",
        updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L)
    )

    data class Snapshot(
        val enabled: Boolean,
        val reason: String,
        val updatedAt: Long
    )

    companion object {
        private const val PREFS_NAME = "cloudyte_rollout_prefs"
        private const val KEY_ENABLED = "transfer_rollout_enabled"
        private const val KEY_REASON = "transfer_rollout_reason"
        private const val KEY_UPDATED_AT = "transfer_rollout_updated_at"
        private const val MAX_REASON_LENGTH = 120
    }
}
