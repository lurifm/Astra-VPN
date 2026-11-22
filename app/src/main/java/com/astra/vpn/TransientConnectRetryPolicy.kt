package com.astra.vpn

/** Bounded retry schedule for explicitly retryable transient VPN start/reload failures. */
internal object TransientConnectRetryPolicy {
    private val delays = longArrayOf(3_000L, 8_000L, 15_000L, 30_000L)

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

    fun shouldDeferUntilActivityStarted(
        activityStarted: Boolean,
        activityFinishing: Boolean,
        activityDestroyed: Boolean
    ): Boolean = !activityStarted && !activityFinishing && !activityDestroyed

    fun shouldRemainPending(
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

    fun shouldExecute(
        generationCurrent: Boolean,
        selectedProfileUnchanged: Boolean,
        activityActive: Boolean,
        userRequestedDisconnect: Boolean,
        connecting: Boolean,
        connected: Boolean,
        disconnecting: Boolean,
        hasValidatedPhysicalInternet: Boolean
    ): Boolean = shouldRemainPending(
        generationCurrent,
        selectedProfileUnchanged,
        activityActive,
        userRequestedDisconnect,
        connecting,
        connected,
        disconnecting
    ) && hasValidatedPhysicalInternet
}
