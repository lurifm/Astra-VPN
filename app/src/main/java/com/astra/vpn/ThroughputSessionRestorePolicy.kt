package com.astra.vpn

/**
 * A throughput probe has a strict 20-second service deadline. A persisted
 * session older than this recovery window is treated as orphaned instead of
 * blindly launching a second probe after process death.
 */
internal object ThroughputSessionRestorePolicy {
    const val MAX_RECOVERABLE_AGE_MS = 35_000L

    fun canRecover(startedAtMillis: Long, nowMillis: Long): Boolean {
        val age = nowMillis - startedAtMillis
        return age in 0L..MAX_RECOVERABLE_AGE_MS
    }
}
