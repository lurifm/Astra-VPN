package com.astra.vpn

/** Converts a monotonic deadline into a safe socket timeout in milliseconds. */
internal object ThroughputTimeoutPolicy {
    fun remainingMillis(deadlineNanos: Long, nowNanos: Long): Int {
        // nanoTime() is a wrapping counter with an arbitrary origin. Compare
        // the signed difference, not the absolute values: a short future
        // deadline remains valid even when the counter wraps Long.MAX_VALUE.
        val remainingNanos = deadlineNanos - nowNanos
        if (remainingNanos <= 0L) {
            // A negative difference with deadline > now can only be the
            // subtraction-overflow case (an unrepresentably distant deadline).
            return if (deadlineNanos > nowNanos) Int.MAX_VALUE else 0
        }
        // Round up: a positive sub-millisecond budget must not become a zero
        // timeout (which Java networking APIs may interpret as infinite).
        val millis = 1L + (remainingNanos - 1L) / 1_000_000L
        return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
