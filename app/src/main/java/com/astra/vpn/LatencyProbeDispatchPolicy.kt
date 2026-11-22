package com.astra.vpn

/** Prevents queued native URLTest work from starting after cancellation or completion. */
internal object LatencyProbeDispatchPolicy {
    fun shouldDispatch(stopped: Boolean, alreadyCompleted: Boolean): Boolean =
        !stopped && !alreadyCompleted
}
