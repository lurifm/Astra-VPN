package com.astra.vpn

/**
 * Exponential retry policy for the libbox traffic-event subscription.
 * Only a connection that stayed up long enough resets the backoff.
 */
internal class TrafficMonitorRetryPolicy(
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val stableConnectionMs: Long = 30_000L
) {
    private var nextDelayMs = initialDelayMs

    @Synchronized
    fun nextDelayAfter(connectedDurationMs: Long): Long {
        if (connectedDurationMs >= stableConnectionMs) {
            nextDelayMs = initialDelayMs
            return initialDelayMs
        }
        val delay = nextDelayMs
        nextDelayMs = (nextDelayMs * 2L).coerceAtMost(maxDelayMs)
        return delay
    }
}
