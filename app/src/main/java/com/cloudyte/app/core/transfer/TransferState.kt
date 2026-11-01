package com.cloudyte.app.core.transfer

/** Durable lifecycle values stored in Room. Keep legacy status columns during migration. */
enum class TransferState {
    QUEUED,
    WAITING_FOR_NETWORK,
    WAITING_FOR_AUTH,
    RUNNING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    EXPIRED;

    fun canTransitionTo(next: TransferState): Boolean = when (this) {
        QUEUED -> next in setOf(RUNNING, WAITING_FOR_NETWORK, WAITING_FOR_AUTH, CANCELLED, EXPIRED)
        WAITING_FOR_NETWORK -> next in setOf(QUEUED, RUNNING, CANCELLED, EXPIRED)
        WAITING_FOR_AUTH -> next in setOf(QUEUED, RUNNING, CANCELLED, EXPIRED)
        RUNNING -> next in setOf(PAUSING, CANCELLING, COMPLETED, FAILED_RETRYABLE, FAILED_PERMANENT, WAITING_FOR_NETWORK, WAITING_FOR_AUTH, EXPIRED)
        PAUSING -> next == PAUSED || next == CANCELLED
        PAUSED -> next == QUEUED || next == CANCELLING || next == CANCELLED
        CANCELLING -> next == CANCELLED
        FAILED_RETRYABLE -> next == QUEUED || next == CANCELLED || next == EXPIRED
        FAILED_PERMANENT, CANCELLED, COMPLETED, EXPIRED -> false
    }

    companion object {
        fun fromLegacyStatus(status: String?): TransferState = when (status?.uppercase()) {
            // Legacy rows did not have leases, so no worker can safely own them after upgrade.
            "RUNNING" -> QUEUED
            "PAUSED" -> PAUSED
            "COMPLETED" -> COMPLETED
            "CANCELLED" -> CANCELLED
            "FAILED" -> FAILED_RETRYABLE
            else -> QUEUED
        }
    }
}
