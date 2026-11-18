package com.astra.vpn

/** Conservative guardrails for automatic server failover. */
internal object AutoFailoverPolicy {
    const val REQUIRED_FAILURES = 3
    const val COOLDOWN_MS = 60_000L

    /** Increment a failure streak without allowing integer wraparound. */
    fun nextFailureCount(current: Int): Int =
        if (current < 0) 1 else if (current == Int.MAX_VALUE) Int.MAX_VALUE else current + 1

    fun shouldSwitch(consecutiveFailures: Int, lastSwitchAt: Long, now: Long): Boolean {
        if (consecutiveFailures < REQUIRED_FAILURES) return false
        if (lastSwitchAt > Long.MAX_VALUE - COOLDOWN_MS) return false
        return now >= lastSwitchAt + COOLDOWN_MS
    }
}
