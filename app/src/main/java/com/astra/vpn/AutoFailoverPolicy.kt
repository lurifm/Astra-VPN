package com.astra.vpn

/** Conservative guardrails for automatic server failover. */
internal object AutoFailoverPolicy {
    const val REQUIRED_FAILURES = 3
    const val COOLDOWN_MS = 60_000L

    fun shouldSwitch(consecutiveFailures: Int, lastSwitchAt: Long, now: Long): Boolean {
        if (consecutiveFailures < REQUIRED_FAILURES) return false
        if (now < lastSwitchAt) return false
        return now - lastSwitchAt >= COOLDOWN_MS
    }
}
