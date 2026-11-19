package com.astra.vpn

/** Keeps periodic health ticks and probe launches scoped to a live VPN service. */
internal object HealthMonitorLifecyclePolicy {
    fun shouldScheduleTick(stopping: Boolean, hasTunDescriptor: Boolean): Boolean =
        !stopping && hasTunDescriptor

    fun shouldStartProbe(
        stopping: Boolean,
        hasTunDescriptor: Boolean,
        isVpnNetwork: Boolean,
        probeInFlight: Boolean
    ): Boolean = !stopping && hasTunDescriptor && isVpnNetwork && !probeInFlight
}
