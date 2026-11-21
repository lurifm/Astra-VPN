package com.astra.vpn

/** Prevents queued native URLTest work from starting after its batch deadline or completion. */
internal object LatencyProbeDispatchPolicy {
    fun shouldDispatch(stopped: Boolean, alreadyCompleted: Boolean, remainingMs: Long): Boolean =
        !stopped && !alreadyCompleted && remainingMs > 0L
}
