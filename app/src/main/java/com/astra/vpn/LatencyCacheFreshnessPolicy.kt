package com.astra.vpn

/** Wall-clock guard for persisted ping snapshots restored after process death. */
internal object LatencyCacheFreshnessPolicy {
    const val DEFAULT_MAX_AGE_MS = 5L * 60L * 1_000L

    fun isFresh(
        savedAtWallClockMs: Long,
        nowWallClockMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS
    ): Boolean {
        if (savedAtWallClockMs <= 0L || nowWallClockMs <= 0L || maxAgeMs <= 0L) return false
        if (savedAtWallClockMs > nowWallClockMs) return false
        return nowWallClockMs - savedAtWallClockMs <= maxAgeMs
    }
}
