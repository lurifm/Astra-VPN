package com.astra.vpn

/** Prevents queued native URLTest work from starting after cancellation or completion. */
internal object LatencyProbeDispatchPolicy {
    fun shouldDispatch(stopped: Boolean, alreadyCompleted: Boolean): Boolean =
        !stopped && !alreadyCompleted
}

/** Timing guard for native URLTest work that outlives the UI deadline. */
internal object NativeUrlTestTimingPolicy {
    const val SERVER_TIMEOUT_MS = 10_000L
    const val NATIVE_MAX_DURATION_MS = 31_000L

    fun cooldownAfterUiTimeoutMs(): Long =
        (NATIVE_MAX_DURATION_MS - SERVER_TIMEOUT_MS).coerceAtLeast(0L)
}
