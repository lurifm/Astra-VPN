package com.astra.vpn

/**
 * A persisted CONNECTED flag is only a hint after Activity/process recreation.
 * Require both Android's VPN transport and Astra's encrypted active-profile
 * marker before presenting this app's tunnel as connected.
 */
internal object VpnUiRestorePolicy {
    fun isAstraTunnelConnected(
        savedState: String?,
        connectedState: String,
        androidVpnTransportActive: Boolean,
        activeProfileAvailable: Boolean
    ): Boolean =
        savedState == connectedState && androidVpnTransportActive && activeProfileAvailable
}
