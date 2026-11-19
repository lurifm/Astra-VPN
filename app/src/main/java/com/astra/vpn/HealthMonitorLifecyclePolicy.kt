package com.astra.vpn

/** Keeps periodic health ticks scoped to a live VPN service and TUN descriptor. */
internal object HealthMonitorLifecyclePolicy {
    fun shouldScheduleTick(stopping: Boolean, hasTunDescriptor: Boolean): Boolean =
        !stopping && hasTunDescriptor
}
