package com.cloudyte.app.core.transfer

import kotlin.math.abs

/** Calculates progress from several samples instead of a single noisy delta. */
class TransferProgressCalculator(
    private val windowSize: Int = 6,
    private val minimumSampleMs: Long = 750L
) {
    private data class Sample(val bytes: Long, val at: Long)
    private val samples = ArrayDeque<Sample>()

    fun update(bytes: Long, totalBytes: Long, at: Long = System.currentTimeMillis()): TransferProgressMetrics {
        val current = Sample(bytes.coerceAtLeast(0L), at)
        val previous = samples.lastOrNull()
        if (previous != null && current.bytes < previous.bytes) samples.clear()
        samples.addLast(current)
        while (samples.size > windowSize) samples.removeFirst()

        val first = samples.firstOrNull()
        val duration = if (first == null) 0L else current.at - first.at
        val delta = if (first == null) 0L else (current.bytes - first.bytes).coerceAtLeast(0L)
        val speed = if (duration >= minimumSampleMs && delta > 0L) (delta * 1000L) / duration else 0L
        val stable = samples.size >= 2 && duration >= minimumSampleMs && speed > 0L && !isOutlier(speed)
        val eta = if (stable && totalBytes > current.bytes) (totalBytes - current.bytes) / speed else null
        return TransferProgressMetrics(
            bytesPerSecond = if (stable) speed else 0L,
            etaSeconds = eta
        )
    }

    fun reset() = samples.clear()

    private fun isOutlier(speed: Long): Boolean {
        if (samples.size < 3) return false
        val intervals = samples.zipWithNext().mapNotNull { (a, b) ->
            val elapsed = b.at - a.at
            if (elapsed >= minimumSampleMs && b.bytes >= a.bytes) (b.bytes - a.bytes) * 1000L / elapsed else null
        }
        if (intervals.size < 2) return false
        val median = intervals.sorted()[intervals.size / 2].coerceAtLeast(1L)
        return abs(speed - median) > median * 3L
    }
}

data class TransferProgressMetrics(
    val bytesPerSecond: Long,
    val etaSeconds: Long?
)
