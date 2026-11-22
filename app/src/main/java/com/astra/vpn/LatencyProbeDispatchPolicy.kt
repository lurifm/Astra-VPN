package com.astra.vpn

/** Prevents queued native URLTest work from starting after cancellation or completion. */
internal object LatencyProbeDispatchPolicy {
    fun shouldDispatch(stopped: Boolean, alreadyCompleted: Boolean): Boolean =
        !stopped && !alreadyCompleted

    /**
     * Outbounds subscription events are complete snapshots. Accept a value only
     * for a probe currently occupying a scheduler slot; this rejects cached
     * values for queued servers and callbacks arriving after native cooldown.
     */
    fun shouldAcceptOutboundUpdate(
        isInFlight: Boolean,
        alreadyCompleted: Boolean,
        previouslyTimedOut: Boolean
    ): Boolean = isInFlight && (!alreadyCompleted || previouslyTimedOut)

    /** Do not tear down libbox while timed-out native probes may still publish results. */
    fun shouldFinish(completedAll: Boolean, nativeSlotsPending: Boolean): Boolean =
        completedAll && !nativeSlotsPending
}

/** Timing guard for native URLTest work that outlives the UI deadline. */
internal object NativeUrlTestTimingPolicy {
    const val SERVER_TIMEOUT_MS = 10_000L
    const val NATIVE_MAX_DURATION_MS = 31_000L

    fun cooldownAfterUiTimeoutMs(): Long =
        (NATIVE_MAX_DURATION_MS - SERVER_TIMEOUT_MS).coerceAtLeast(0L)
}
