package com.astra.vpn

/**
 * Separates loss of the device's physical Internet path from a failed VPN
 * tunnel. Never probe/fail over a VPN server while the underlay is offline.
 */
internal object NetworkRecoveryPolicy {
    fun shouldProbeTunnel(hasValidatedPhysicalInternet: Boolean): Boolean =
        hasValidatedPhysicalInternet
}
