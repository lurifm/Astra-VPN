package com.astra.vpn

/** Validates monotonic timestamps on service health broadcasts before UI/failover use. */
internal object ServiceHealthResultPolicy {
    const val MAX_AGE_MS = 30_000L

    fun isFresh(checkedAtElapsedMs: Long, nowElapsedMs: Long): Boolean {
        if (checkedAtElapsedMs <= 0L || nowElapsedMs < 0L || nowElapsedMs < checkedAtElapsedMs) return false
        return nowElapsedMs - checkedAtElapsedMs <= MAX_AGE_MS
    }
}
