package com.astra.vpn

/** Bounds repeated Android foreground-service start attempts after auto-resume failures. */
internal object BankingResumeRetryPolicy {
    const val MIN_RETRY_INTERVAL_MS = 10_000L

    fun shouldAttempt(lastAttemptAtEpochMillis: Long, nowEpochMillis: Long): Boolean {
        if (lastAttemptAtEpochMillis <= 0L) return true
        if (nowEpochMillis < lastAttemptAtEpochMillis) return false
        return nowEpochMillis - lastAttemptAtEpochMillis >= MIN_RETRY_INTERVAL_MS
    }
}
