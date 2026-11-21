package com.astra.vpn

/**
 * Prevents delayed non-terminal service broadcasts from erasing a persisted
 * user Disconnect request before the service has actually stopped the tunnel.
 */
internal object DisconnectRequestPolicy {
    fun shouldClearAfterServiceState(state: String?): Boolean =
        state == "disconnected"

    /**
     * The persisted service state is a terminal acknowledgement only when it is
     * DISCONNECTED. A missing VPN route by itself is not proof that ACTION_STOP
     * finished: Android may remove the route before the service records its state.
     */
    fun shouldClearAfterPersistedState(state: String?): Boolean =
        shouldClearAfterServiceState(state)
}
