package com.astra.vpn

/**
 * Prevents delayed non-terminal service broadcasts from erasing a persisted
 * user Disconnect request before the service has actually stopped the tunnel.
 */
internal object DisconnectRequestPolicy {
    fun shouldClearAfterServiceState(state: String?): Boolean =
        state == "disconnected"
}
