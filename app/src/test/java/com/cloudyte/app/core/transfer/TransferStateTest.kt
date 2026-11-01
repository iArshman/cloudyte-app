package com.cloudyte.app.core.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStateTest {
    @Test fun queuedTransitionsAreConstrained() {
        assertTrue(TransferState.QUEUED.canTransitionTo(TransferState.RUNNING))
        assertTrue(TransferState.QUEUED.canTransitionTo(TransferState.WAITING_FOR_NETWORK))
        assertFalse(TransferState.QUEUED.canTransitionTo(TransferState.COMPLETED))
    }

    @Test fun terminalStatesCannotBeReopenedWithoutExplicitRetry() {
        assertFalse(TransferState.COMPLETED.canTransitionTo(TransferState.QUEUED))
        assertFalse(TransferState.CANCELLED.canTransitionTo(TransferState.RUNNING))
        assertTrue(TransferState.FAILED_RETRYABLE.canTransitionTo(TransferState.QUEUED))
    }

    @Test fun legacyStatusBackfillIsDeterministic() {
        assertTrue(TransferState.fromLegacyStatus("PENDING") == TransferState.QUEUED)
        assertTrue(TransferState.fromLegacyStatus("RUNNING") == TransferState.QUEUED)
        assertTrue(TransferState.fromLegacyStatus("FAILED") == TransferState.FAILED_RETRYABLE)
    }
}
