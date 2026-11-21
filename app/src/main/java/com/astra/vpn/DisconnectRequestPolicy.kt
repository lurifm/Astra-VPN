package com.astra.vpn

/**
 * Prevents delayed non-terminal service broadcasts from erasing a persisted
 * user Disconnect request before the service has actually stopped the tunnel.
 */
internal object DisconnectRequestPolicy {
    fun shouldClearAfterServiceState(state: String?): Boolean =
        state == "disconnected"

    /**
     * Persisted state is only a snapshot and carries no ordering relative to the
     * user's stop request. It can never acknowledge a pending stop safely.
     */
    fun shouldClearAfterPersistedState(state: String?): Boolean = false
}
