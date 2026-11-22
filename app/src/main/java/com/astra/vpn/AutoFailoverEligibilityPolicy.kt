package com.astra.vpn

/** Last-moment guard for server failover after end-to-end tunnel health checks. */
internal object AutoFailoverEligibilityPolicy {
    fun shouldSwitch(
        connected: Boolean,
        connecting: Boolean,
        disconnecting: Boolean,
        selectingFastest: Boolean,
        hasPendingFailover: Boolean,
        tunnelInternetOk: Boolean?,
        hasValidatedPhysicalInternet: Boolean
    ): Boolean =
        connected &&
            !connecting &&
            !disconnecting &&
            !selectingFastest &&
            !hasPendingFailover &&
            tunnelInternetOk == false &&
            hasValidatedPhysicalInternet
}
