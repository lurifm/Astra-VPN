package com.astra.vpn

/**
 * Manual disconnect is authoritative while Android is still acknowledging the
 * stop request. Automatic monitoring/recovery must not resurrect the tunnel.
 */
internal object VpnActionAdmissionPolicy {
    fun shouldRunHealthCheck(
        connected: Boolean,
        disconnecting: Boolean,
        isFinishing: Boolean,
        isDestroyed: Boolean
    ): Boolean = connected && !disconnecting && !isFinishing && !isDestroyed

    fun shouldStartSelectedProfile(
        connecting: Boolean,
        disconnecting: Boolean,
        hasPendingConfig: Boolean
    ): Boolean = !connecting && !disconnecting && !hasPendingConfig

    fun shouldAutoFailover(
        connected: Boolean,
        connecting: Boolean,
        disconnecting: Boolean,
        selectingFastest: Boolean
    ): Boolean = connected && !connecting && !disconnecting && !selectingFastest
}
