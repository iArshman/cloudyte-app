package com.cloudyte.app.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressTest {
    @Test
    fun speedRemainsUnavailableUntilMinimumSampleWindow() {
        val calculator = TransferProgressCalculator(minimumSampleMs = 500L)
        assertEquals(0L, calculator.update(0L, 1_000L, 0L).bytesPerSecond)
        assertEquals(0L, calculator.update(500L, 1_000L, 400L).bytesPerSecond)
        assertTrue(calculator.update(1_000L, 1_000L, 800L).bytesPerSecond > 0L)
    }

    @Test
    fun rollingSpeedProducesEtaAndResetsAfterRetryRollback() {
        val calculator = TransferProgressCalculator(minimumSampleMs = 100L)
        calculator.update(0L, 2_000L, 0L)
        calculator.update(500L, 2_000L, 500L)
        val metrics = calculator.update(1_000L, 2_000L, 1_000L)
        assertEquals(1_000L, metrics.bytesPerSecond)
        assertEquals(1L, metrics.etaSeconds)

        calculator.update(200L, 2_000L, 1_100L)
        assertEquals(0L, calculator.update(400L, 2_000L, 1_200L).bytesPerSecond)
    }
}
