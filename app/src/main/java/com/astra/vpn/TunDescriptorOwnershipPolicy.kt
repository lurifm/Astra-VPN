package com.astra.vpn

/**
 * Decides whether a TUN descriptor may be transferred from AstraPlatform
 * to the currently active Android VPN service lifecycle.
 */
internal object TunDescriptorOwnershipPolicy {
    fun shouldAccept(stopping: Boolean, lifecycleIsCurrent: Boolean): Boolean =
        !stopping && lifecycleIsCurrent
}
