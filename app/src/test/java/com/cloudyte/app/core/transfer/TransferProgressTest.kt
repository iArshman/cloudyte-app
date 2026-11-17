package com.cloudyte.app.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressTest {
    @Test
    fun speedRemainsUnavailableUntilMinimumSampleWindowThenEmitsImmediately() {
        val calculator = TransferProgressCalculator(minimumSampleMs = 500L)
        assertEquals(0L, calculator.update(0L, 1_000L, 0L).bytesPerSecond)
        assertEquals(0L, calculator.update(500L, 1_000L, 400L).bytesPerSecond)
        val firstReading = calculator.update(1_000L, 1_000L, 800L)
        assertEquals(1_250L, firstReading.bytesPerSecond)
    }

    @Test
    fun emaSmoothsSpikesProducesEtaAndResetsAfterRetryRollback() {
        val calculator = TransferProgressCalculator(minimumSampleMs = 100L, smoothingFactor = 0.35)
        calculator.update(0L, 2_000L, 0L)
        calculator.update(500L, 2_000L, 500L)
        val metrics = calculator.update(1_000L, 2_000L, 1_000L)
        assertEquals(1_000L, metrics.bytesPerSecond)
        assertEquals(1L, metrics.etaSeconds)

        // Sudden burst should be smoothed by EMA (0.35 * 10000 + 0.65 * 1000 = 4150), not raw 10000
        val spiked = calculator.update(2_000L, 4_000L, 1_100L)
        assertEquals(4_150L, spiked.bytesPerSecond)

        // Retry rollback clears accumulated EMA state cleanly
        assertEquals(0L, calculator.update(200L, 2_000L, 1_150L).bytesPerSecond)
        val freshAfterRollback = calculator.update(400L, 2_000L, 1_350L)
        assertEquals(1_000L, freshAfterRollback.bytesPerSecond)
    }
}
