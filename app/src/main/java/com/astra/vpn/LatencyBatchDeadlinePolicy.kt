package com.astra.vpn

/**
 * Uses one monotonic absolute deadline for the complete latency batch.
 * Startup time and individual URL-test time therefore consume the same budget.
 */
internal object LatencyBatchDeadlinePolicy {
    fun deadlineAt(startedAtElapsedMs: Long, timeoutMs: Long): Long {
        val start = startedAtElapsedMs.coerceAtLeast(0L)
        if (timeoutMs <= 0L) return start
        return if (Long.MAX_VALUE - start < timeoutMs) Long.MAX_VALUE else start + timeoutMs
    }

    fun remainingMs(deadlineElapsedMs: Long, nowElapsedMs: Long): Long {
        if (deadlineElapsedMs <= 0L || nowElapsedMs < 0L || nowElapsedMs >= deadlineElapsedMs) return 0L
        return deadlineElapsedMs - nowElapsedMs
    }
}
