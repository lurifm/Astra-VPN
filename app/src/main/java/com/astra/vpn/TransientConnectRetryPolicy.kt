package com.astra.vpn

/** Bounded retry schedule for a transient TUN-creation timeout only. */
internal object TransientConnectRetryPolicy {
    private val delays = longArrayOf(3_000L, 8_000L)

    fun delayForAttempt(attempt: Int): Long? =
        delays.getOrNull(attempt.coerceAtLeast(0))

    fun shouldSchedule(
        retryable: Boolean,
        isAutomaticFailover: Boolean,
        userRequestedDisconnect: Boolean,
        hasSelectedProfile: Boolean,
        attempt: Int
    ): Boolean = retryable &&
        !isAutomaticFailover &&
        !userRequestedDisconnect &&
        hasSelectedProfile &&
        delayForAttempt(attempt) != null

    fun shouldExecute(
        generationCurrent: Boolean,
        selectedProfileUnchanged: Boolean,
        activityActive: Boolean,
        userRequestedDisconnect: Boolean,
        connecting: Boolean,
        connected: Boolean,
        disconnecting: Boolean
    ): Boolean = generationCurrent &&
        selectedProfileUnchanged &&
        activityActive &&
        !userRequestedDisconnect &&
        !connecting &&
        !connected &&
        !disconnecting
}
