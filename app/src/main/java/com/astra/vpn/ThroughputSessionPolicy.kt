package com.astra.vpn

/** Prevents delayed cancellation broadcasts from stopping a different probe. */
internal object ThroughputSessionPolicy {
    const val WATCHDOG_TIMEOUT_MS = 35_000L

    fun remainingWatchdogDelayMillis(startedAtMillis: Long, nowMillis: Long): Long {
        if (startedAtMillis <= 0L || nowMillis < startedAtMillis) return 0L
        return (WATCHDOG_TIMEOUT_MS - (nowMillis - startedAtMillis)).coerceAtLeast(0L)
    }

    fun isWatchdogExpired(startedAtMillis: Long, nowMillis: Long): Boolean =
        startedAtMillis <= 0L || nowMillis < startedAtMillis ||
            nowMillis - startedAtMillis >= WATCHDOG_TIMEOUT_MS

    fun shouldStop(activeSessionId: String?, requestedSessionId: String?): Boolean =
        !activeSessionId.isNullOrBlank() &&
            !requestedSessionId.isNullOrBlank() &&
            activeSessionId == requestedSessionId
}
