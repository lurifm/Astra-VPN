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

    init {
        require(initialDelayMs > 0L) { "Initial retry delay must be positive" }
        require(maxDelayMs >= initialDelayMs) { "Maximum retry delay must be at least the initial delay" }
        require(stableConnectionMs >= 0L) { "Stable connection duration cannot be negative" }
    }

    @Synchronized
    fun nextDelayAfter(connectedDurationMs: Long): Long {
        if (connectedDurationMs >= stableConnectionMs) {
            nextDelayMs = initialDelayMs
            return initialDelayMs
        }
        val delay = nextDelayMs
        // Saturate before multiplication so custom large limits cannot overflow Long.
        nextDelayMs = if (nextDelayMs >= maxDelayMs / 2L) {
            maxDelayMs
        } else {
            (nextDelayMs * 2L).coerceAtMost(maxDelayMs)
        }
        return delay
    }
}
