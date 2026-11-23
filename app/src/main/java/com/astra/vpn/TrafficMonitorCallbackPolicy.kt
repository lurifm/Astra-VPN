package com.astra.vpn

/** Rejects traffic callbacks that no longer belong to the attached monitor. */
internal object TrafficMonitorCallbackPolicy {
    fun shouldPublish(
        ownerGeneration: Long,
        currentGeneration: Long,
        monitorAttached: Boolean,
        stopping: Boolean
    ): Boolean = !stopping && monitorAttached && ownerGeneration == currentGeneration
}
