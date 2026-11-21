package com.astra.vpn

import java.util.ArrayDeque

/**
 * Rolling-window circuit breaker for automatic server switching.
 * Prevents a set of unhealthy nodes from causing an endless failover carousel.
 */
internal class AutoFailoverBudget(
    private val maxAttempts: Int = 3,
    private val windowMs: Long = 10 * 60 * 1_000L
) {
    private val attempts = ArrayDeque<Long>()
    private var lastObservedAt = Long.MIN_VALUE

    init {
        require(maxAttempts > 0)
        require(windowMs > 0L)
    }

    @Synchronized
    fun canAttempt(now: Long): Boolean {
        if (now < 0L) return false
        val clockMovedBackwards = lastObservedAt != Long.MIN_VALUE && now < lastObservedAt
        if (clockMovedBackwards) {
            // Monotonic timestamps should not move backwards. Discard the old
            // window and fail closed for this observation; the next timestamp
            // can start a fresh window without permanently locking failover.
            attempts.clear()
        }
        lastObservedAt = now
        prune(now)
        return !clockMovedBackwards && attempts.size < maxAttempts
    }

    @Synchronized
    fun recordAttempt(now: Long): Boolean {
        if (!canAttempt(now)) return false
        attempts.addLast(now)
        return true
    }

    @Synchronized
    fun attemptsInWindow(now: Long): Int {
        if (now < 0L) return 0
        if (lastObservedAt != Long.MIN_VALUE && now < lastObservedAt) attempts.clear()
        lastObservedAt = now
        prune(now)
        return attempts.size
    }

    private fun prune(now: Long) {
        val cutoff = if (now < windowMs) 0L else now - windowMs
        while (attempts.isNotEmpty() && attempts.peekFirst() <= cutoff) {
            attempts.removeFirst()
        }
    }
}
