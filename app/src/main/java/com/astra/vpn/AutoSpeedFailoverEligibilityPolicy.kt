package com.astra.vpn

/** Speed-drop failover is only meaningful after the active VPN path is healthy. */
internal object AutoSpeedFailoverEligibilityPolicy {
    fun shouldEvaluate(
        connected: Boolean,
        connecting: Boolean,
        tunnelInternetOk: Boolean?,
        selectingFastest: Boolean,
        hasPendingFailover: Boolean,
        enabled: Boolean
    ): Boolean =
        connected &&
            !connecting &&
            tunnelInternetOk == true &&
            !selectingFastest &&
            !hasPendingFailover &&
            enabled
}
