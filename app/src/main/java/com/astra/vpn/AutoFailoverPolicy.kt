package com.astra.vpn

/** Conservative guardrails for automatic server failover. */
internal object AutoFailoverPolicy {
    const val REQUIRED_FAILURES = 3
    const val COOLDOWN_MS = 60_000L
    const val MAX_COOLDOWN_MS = 3L * 60L * 1_000L

    /**
     * Increase the minimum interval after each automatic hop in the rolling
     * window. This slows recovery loops without disabling the final fallback.
     */
    fun cooldownMs(attemptsInWindow: Int): Long {
        if (attemptsInWindow <= 0) return COOLDOWN_MS
        var cooldown = COOLDOWN_MS
        repeat((attemptsInWindow - 1).coerceAtMost(2)) {
            cooldown = (cooldown * 2L).coerceAtMost(MAX_COOLDOWN_MS)
        }
        return cooldown
    }

    /** Increment a failure streak without allowing integer wraparound. */
    fun nextFailureCount(current: Int): Int =
        if (current < 0) 1 else if (current == Int.MAX_VALUE) Int.MAX_VALUE else current + 1

    /** Shared cooldown gate for every automatic switch trigger. */
    fun cooldownElapsed(lastSwitchAt: Long, now: Long, cooldownMs: Long): Boolean {
        // Timestamps come from elapsedRealtime; invalid values fail closed.
        if (lastSwitchAt < 0L || now < 0L || cooldownMs <= 0L) return false
        if (lastSwitchAt > Long.MAX_VALUE - cooldownMs) return false
        return now >= lastSwitchAt + cooldownMs
    }

    fun shouldSwitch(
        consecutiveFailures: Int,
        lastSwitchAt: Long,
        now: Long,
        cooldownMs: Long = COOLDOWN_MS
    ): Boolean = consecutiveFailures >= REQUIRED_FAILURES &&
        cooldownElapsed(lastSwitchAt, now, cooldownMs)
}
