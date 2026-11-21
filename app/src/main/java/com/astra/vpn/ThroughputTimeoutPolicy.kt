package com.astra.vpn

/** Converts a monotonic deadline into a safe socket timeout in milliseconds. */
internal object ThroughputTimeoutPolicy {
    fun remainingMillis(deadlineNanos: Long, nowNanos: Long): Int {
        val remainingNanos = deadlineNanos - nowNanos
        if (remainingNanos <= 0L) return 0
        // Round up: a positive sub-millisecond budget must not become a zero
        // timeout (which Java networking APIs may interpret as infinite).
        val millis = 1L + (remainingNanos - 1L) / 1_000_000L
        return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
