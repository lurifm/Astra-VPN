package com.astra.vpn

/** Prevents queued native URLTest work from starting after cancellation or completion. */
internal object LatencyProbeDispatchPolicy {
    fun shouldDispatch(
        stopped: Boolean,
        alreadyCompleted: Boolean,
        handoverPending: Boolean = false,
        engineRecycleInProgress: Boolean = false
    ): Boolean = !stopped && !alreadyCompleted && !handoverPending && !engineRecycleInProgress

    /** Outbounds subscription events are complete snapshots; accept only active, current probes. */
    fun shouldAcceptOutboundUpdate(
        isInFlight: Boolean,
        alreadyCompleted: Boolean,
        previouslyTimedOut: Boolean,
        discardedByNetworkChange: Boolean = false,
        handoverPending: Boolean = false,
        engineRecycleInProgress: Boolean = false
    ): Boolean = !handoverPending && !engineRecycleInProgress &&
        !discardedByNetworkChange && isInFlight && (!alreadyCompleted || previouslyTimedOut)

    /** Reject an outbound snapshot whose URLTest history predates this dispatch. */
    fun isResultFresh(dispatchedAtEpochSeconds: Long?, resultAtEpochSeconds: Long): Boolean =
        dispatchedAtEpochSeconds != null && resultAtEpochSeconds >= dispatchedAtEpochSeconds

    fun shouldFinish(completedAll: Boolean, engineRecycleInProgress: Boolean): Boolean =
        completedAll && !engineRecycleInProgress
}

/** Guards the serialized lifecycle of the isolated sing-box URLTest engine. */
internal object LatencyEngineRecyclePolicy {
    const val SERVER_TIMEOUT_MS = 10_000L
    const val RECYCLE_TIMEOUT_MS = 20_000L

    fun shouldStartRecycle(stopped: Boolean, alreadyRecycling: Boolean): Boolean =
        !stopped && !alreadyRecycling

    fun shouldDispatch(stopped: Boolean, recycling: Boolean, handoverPending: Boolean): Boolean =
        !stopped && !recycling && !handoverPending

    fun shouldAcceptResult(recycling: Boolean, expectedGeneration: Long, currentGeneration: Long): Boolean =
        !recycling && expectedGeneration == currentGeneration

    fun shouldRequeueAfterHandover(previousRetries: Int, hasValidatedUnderlay: Boolean): Boolean =
        NetworkRecoveryPolicy.shouldRequeueAfterHandover(previousRetries, hasValidatedUnderlay)
}
