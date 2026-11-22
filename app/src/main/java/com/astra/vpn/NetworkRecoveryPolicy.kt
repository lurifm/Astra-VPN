package com.astra.vpn

/**
 * Separates loss of the device's physical Internet path from a failed VPN
 * tunnel. Never probe/fail over a VPN server while the underlay is offline.
 */
internal object NetworkRecoveryPolicy {
    // Coalesce the burst of availability/capability/link-property callbacks
    // emitted by Android for one physical network handover.
    const val NETWORK_CHANGE_DEBOUNCE_MS = 250L

    fun shouldProbeTunnel(hasValidatedPhysicalInternet: Boolean): Boolean =
        hasValidatedPhysicalInternet

    fun retryDelayMs(attempt: Int): Long? = when (attempt) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        else -> null
    }

    fun shouldPublishUnderlay(
        managesAndroidUnderlay: Boolean,
        updateRequired: Boolean,
        updateSucceeded: Boolean
    ): Boolean = !managesAndroidUnderlay || !updateRequired || updateSucceeded

    fun isValidatedPhysicalUnderlay(
        isVpn: Boolean,
        hasInternet: Boolean,
        isValidated: Boolean,
        isNotSuspended: Boolean
    ): Boolean = !isVpn && hasInternet && isValidated && isNotSuspended
}
