package com.cloudyte.app.core.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableTransferTest {
    @Test
    fun checksumIsDeterministic() {
        val file = File.createTempFile("cloudyte", ".bin")
        try {
            file.writeText("cloudyte-transfer")
            assertEquals(
                "feb5165ecd9b8f6bbce5c7a0193ac7d76ec669debb117fbd7c56f9ea69ac93a7",
                ResumableTransfer.sha256(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun idempotencyKeyIsStableForTransfer() {
        assertEquals("cloudyte-transfer-123", ResumableTransfer.newIdempotencyKey("transfer-123"))
        assertEquals(
            ResumableTransfer.newIdempotencyKey("transfer-123"),
            ResumableTransfer.newSession("transfer-123", 100L).idempotencyKey
        )
    }

    @Test
    fun retryPolicyUsesBoundedExponentialBackoff() {
        val policy = RetryPolicy(maxAttempts = 3)
        assertTrue(policy.delayForAttempt(2) > policy.delayForAttempt(1))
        assertTrue(policy.canRetry(1))
        assertTrue(!policy.canRetry(3))
    }
}
