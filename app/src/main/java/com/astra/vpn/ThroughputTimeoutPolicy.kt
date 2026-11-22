package com.astra.vpn

/** Converts a monotonic deadline into a safe socket timeout in milliseconds. */
internal object ThroughputTimeoutPolicy {
    fun remainingMillis(deadlineNanos: Long, nowNanos: Long): Int {
        if (deadlineNanos <= nowNanos) return 0
        val remainingNanos = deadlineNanos - nowNanos
        // nanoTime() has an arbitrary origin and may be negative. Subtracting
        // opposite-signed extremes can overflow, so saturate instead of
        // accidentally treating a very distant deadline as already expired.
        if (remainingNanos < 0L) return Int.MAX_VALUE
        // Round up: a positive sub-millisecond budget must not become a zero
        // timeout (which Java networking APIs may interpret as infinite).
        val millis = 1L + (remainingNanos - 1L) / 1_000_000L
        return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
