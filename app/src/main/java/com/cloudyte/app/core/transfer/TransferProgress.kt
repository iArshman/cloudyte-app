package com.cloudyte.app.core.transfer

/**
 * Calculates EMA-smoothed transfer speed and ETA on fixed tick intervals
 * (default 1 second, smoothing alpha = 0.35) with monotonic rollback reset.
 */
class TransferProgressCalculator(
    @Suppress("UNUSED_PARAMETER")
    private val windowSize: Int = 6,
    private val minimumSampleMs: Long = 1_000L,
    private val smoothingFactor: Double = DEFAULT_EMA_ALPHA
) {
    private val alpha = smoothingFactor.coerceIn(0.05, 1.0)
    private var lastSampleAt: Long = -1L
    private var lastSampleBytes: Long = -1L
    private var emaBytesPerSecond: Double = 0.0
    private var lastPublishedSpeed: Long = 0L
    private var lastPublishedEta: Long? = null

    fun update(bytes: Long, totalBytes: Long, at: Long = System.currentTimeMillis()): TransferProgressMetrics {
        val currentBytes = bytes.coerceAtLeast(0L)
        if (lastSampleAt < 0L || currentBytes < lastSampleBytes || at < lastSampleAt) {
            lastSampleAt = at
            lastSampleBytes = currentBytes
            emaBytesPerSecond = 0.0
            lastPublishedSpeed = 0L
            lastPublishedEta = null
            return TransferProgressMetrics(bytesPerSecond = 0L, etaSeconds = null)
        }

        val elapsedMs = at - lastSampleAt
        val minIntervalMs = minimumSampleMs.coerceAtLeast(1L)
        if (elapsedMs < minIntervalMs) {
            val eta = if (lastPublishedSpeed > 0L && totalBytes > currentBytes) {
                (totalBytes - currentBytes) / lastPublishedSpeed
            } else {
                lastPublishedEta
            }
            return TransferProgressMetrics(
                bytesPerSecond = lastPublishedSpeed,
                etaSeconds = eta
            )
        }

        val deltaBytes = (currentBytes - lastSampleBytes).coerceAtLeast(0L)
        lastSampleAt = at
        lastSampleBytes = currentBytes

        val instantBps = (deltaBytes.toDouble() * 1000.0) / elapsedMs.toDouble()
        emaBytesPerSecond = if (emaBytesPerSecond <= 0.0) {
            instantBps
        } else {
            (alpha * instantBps) + ((1.0 - alpha) * emaBytesPerSecond)
        }

        val speed = emaBytesPerSecond.toLong().coerceAtLeast(0L)
        lastPublishedSpeed = speed
        val eta = if (speed > 0L && totalBytes > currentBytes) {
            (totalBytes - currentBytes) / speed
        } else {
            null
        }
        lastPublishedEta = eta
        return TransferProgressMetrics(
            bytesPerSecond = speed,
            etaSeconds = eta
        )
    }

    fun reset() {
        lastSampleAt = -1L
        lastSampleBytes = -1L
        emaBytesPerSecond = 0.0
        lastPublishedSpeed = 0L
        lastPublishedEta = null
    }

    companion object {
        const val DEFAULT_EMA_ALPHA = 0.35
    }
}

data class TransferProgressMetrics(
    val bytesPerSecond: Long,
    val etaSeconds: Long?
)
