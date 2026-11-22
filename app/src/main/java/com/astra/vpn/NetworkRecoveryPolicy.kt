package com.astra.vpn

/**
 * Separates loss of the device's physical Internet path from a failed VPN
 * tunnel. Never probe/fail over a VPN server while the underlay is offline.
 */
internal object NetworkRecoveryPolicy {
    // Coalesce the burst of availability/capability/link-property callbacks
    // emitted by Android for one physical network handover.
    const val NETWORK_CHANGE_DEBOUNCE_MS = 250L
    const val MAX_HANDOVER_RETRIES = 2

    fun shouldRetryAfterHandover(previousRetries: Int): Boolean =
        previousRetries in 0 until MAX_HANDOVER_RETRIES

    fun monitorIntervalMs(
        appBatterySaverEnabled: Boolean,
        systemBatterySaverEnabled: Boolean
    ): Long = if (appBatterySaverEnabled || systemBatterySaverEnabled) 4_000L else 1_000L

    fun shouldProbeTunnel(hasValidatedPhysicalInternet: Boolean): Boolean =
        hasValidatedPhysicalInternet

    fun retryDelayMs(attempt: Int): Long? = when (attempt) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        else -> null
    }

    /** Retain a native slot after underlay loss because URLTest cannot be cancelled per tag. */
    fun probeHandoverNativeCooldownMs(): Long =
        NativeUrlTestTimingPolicy.NATIVE_MAX_DURATION_MS + NativeUrlTestTimingPolicy.SAFETY_MARGIN_MS

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

    /** A probe socket must never fall back to a captive, suspended, or VPN route. */
    fun isProbeSocketNetworkEligible(
        isVpn: Boolean,
        hasInternet: Boolean,
        isValidated: Boolean,
        isNotSuspended: Boolean
    ): Boolean = isValidatedPhysicalUnderlay(
        isVpn = isVpn,
        hasInternet = hasInternet,
        isValidated = isValidated,
        isNotSuspended = isNotSuspended
    )
}
