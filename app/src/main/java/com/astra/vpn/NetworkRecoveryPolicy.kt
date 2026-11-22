package com.astra.vpn

/**
 * Separates loss of the device's physical Internet path from a failed VPN
 * tunnel. Never probe/fail over a VPN server while the underlay is offline.
 */
internal object NetworkRecoveryPolicy {
    // Coalesce the burst of availability/capability/link-property callbacks
    // emitted by Android for one physical network handover.
    const val NETWORK_CHANGE_DEBOUNCE_MS = 250L
    // Let Android finish routing/DNS handover before interpreting HTTPS timeouts as server failures.
    const val HANDOVER_SETTLE_GRACE_MS = 2_000L
    const val MAX_HANDOVER_RETRIES = 2

    fun shouldRetryAfterHandover(previousRetries: Int): Boolean =
        previousRetries in 0 until MAX_HANDOVER_RETRIES

    /**
     * Keep interrupted probes queued while the device has no validated underlay.
     * An outage is not a failed retry and must not consume the handover retry budget.
     */
    fun shouldRequeueAfterHandover(
        previousRetries: Int,
        hasValidatedUnderlay: Boolean
    ): Boolean = !hasValidatedUnderlay || shouldRetryAfterHandover(previousRetries)

    fun isLatestNetworkChange(expectedGeneration: Long, currentGeneration: Long): Boolean =
        expectedGeneration == currentGeneration

    fun monitorIntervalMs(
        appBatterySaverEnabled: Boolean,
        systemBatterySaverEnabled: Boolean
    ): Long = if (appBatterySaverEnabled || systemBatterySaverEnabled) 4_000L else 1_000L

    fun shouldProbeTunnel(hasValidatedPhysicalInternet: Boolean): Boolean =
        hasValidatedPhysicalInternet

    /**
     * A completed HTTPS probe is attributable to the VPN server only when it
     * belongs to the latest tunnel/network generation and the physical route
     * has remained validated through Android's handover grace period.
     */
    fun shouldAttributeProbeResult(
        expectedGeneration: Long,
        currentGeneration: Long,
        hasValidatedUnderlay: Boolean,
        lastPhysicalNetworkChangeAt: Long,
        now: Long
    ): Boolean {
        if (!isLatestNetworkChange(expectedGeneration, currentGeneration) ||
            !hasValidatedUnderlay || now < 0L
        ) return false
        return lastPhysicalNetworkChangeAt == 0L ||
            hasHandoverSettled(lastPhysicalNetworkChangeAt, now)
    }

    /** Do not attribute transient routing convergence failures to the VPN server. */
    fun hasHandoverSettled(lastChangeAt: Long, now: Long): Boolean {
        if (lastChangeAt < 0L || now < 0L || now < lastChangeAt) return false
        return now - lastChangeAt >= HANDOVER_SETTLE_GRACE_MS
    }

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

    /** Keep the caller-selected underlay while it remains usable, regardless of callback order. */
    fun shouldAdoptProbeNetwork(
        currentPreferredIsUsable: Boolean,
        callbackIsPreferred: Boolean
    ): Boolean = !currentPreferredIsUsable || callbackIsPreferred

    /**
     * A ready platform adapter is authoritative, including when it finds no
     * usable network. Callback snapshots are only a startup fallback before
     * the adapter exists; using them after a null refresh can resurrect a
     * stale underlay during an outage.
     */
    fun <T> chooseProbeFallback(
        platformReady: Boolean,
        refreshedCandidate: T?,
        callbackCandidate: T?
    ): T? = if (platformReady) refreshedCandidate else callbackCandidate

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
