package com.cloudyte.app.core.transfer

import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RetryPolicy(
    val maxAttempts: Int = 5,
    private val initialDelay: Duration = 30.seconds,
    private val maxDelay: Duration = 15.minutes
) {
    fun delayForAttempt(attempt: Int): Duration {
        if (attempt <= 0) return Duration.ZERO
        val multiplier = 1 shl min(attempt - 1, 10)
        return min(initialDelay * multiplier, maxDelay)
    }

    fun canRetry(attempt: Int): Boolean = attempt < maxAttempts
}
