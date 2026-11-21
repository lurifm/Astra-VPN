package com.astra.vpn

/** Bounded retry schedule for a transient TUN-creation timeout only. */
internal object TransientConnectRetryPolicy {
    private val delays = longArrayOf(3_000L, 8_000L)

    fun delayForAttempt(attempt: Int): Long? =
        delays.getOrNull(attempt.coerceAtLeast(0))
}
