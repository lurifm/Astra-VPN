package com.astra.vpn

/** Prevents queued native URLTest work from starting after cancellation or completion. */
internal object LatencyProbeDispatchPolicy {
    fun shouldDispatch(
        stopped: Boolean,
        alreadyCompleted: Boolean,
        handoverPending: Boolean = false
    ): Boolean = !stopped && !alreadyCompleted && !handoverPending

    /**
     * Outbounds subscription events are complete snapshots. Accept a value only
     * for a probe currently occupying a scheduler slot; this rejects cached
     * values for queued servers and callbacks arriving after native cooldown.
     */
    fun shouldAcceptOutboundUpdate(
        isInFlight: Boolean,
        alreadyCompleted: Boolean,
        previouslyTimedOut: Boolean,
        discardedByNetworkChange: Boolean = false,
        handoverPending: Boolean = false
    ): Boolean = !handoverPending && !discardedByNetworkChange &&
        isInFlight && (!alreadyCompleted || previouslyTimedOut)

    /** Do not tear down libbox while timed-out native probes may still publish results. */
    fun shouldFinish(completedAll: Boolean, nativeSlotsPending: Boolean): Boolean =
        completedAll && !nativeSlotsPending
}

/**
 * Timing guard for the asynchronous URLTest implementation bundled in sing-box 1.14.3.
 *
 * The pinned native implementation can spend time in three sequential phases:
 * DNS resolution (DNSTimeout), TCP connection (TCPConnectTimeout), and the HTTP
 * request/TLS handshake (http.Client.Timeout / TCPTimeout). The extra second
 * is a safety margin for scheduling and callback delivery.
 *
 * These values mirror constant/timeout.go in SagerNet/sing-box v1.14.3:
 * DNSTimeout=10s, TCPConnectTimeout=5s, TCPTimeout=15s.
 */
internal object NativeUrlTestTimingPolicy {
    const val SERVER_TIMEOUT_MS = 10_000L
    const val DNS_TIMEOUT_MS = 10_000L
    const val TCP_CONNECT_TIMEOUT_MS = 5_000L
    const val HTTP_CLIENT_TIMEOUT_MS = 15_000L
    const val SAFETY_MARGIN_MS = 1_000L

    const val NATIVE_MAX_DURATION_MS =
        DNS_TIMEOUT_MS + TCP_CONNECT_TIMEOUT_MS + HTTP_CLIENT_TIMEOUT_MS + SAFETY_MARGIN_MS

    fun cooldownAfterUiTimeoutMs(): Long =
        (NATIVE_MAX_DURATION_MS - SERVER_TIMEOUT_MS).coerceAtLeast(0L)
}
