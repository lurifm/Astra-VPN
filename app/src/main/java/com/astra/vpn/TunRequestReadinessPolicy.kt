package com.astra.vpn

/**
 * A previously owned TUN descriptor is not proof that a replacement profile
 * established its own Android interface. Readiness is bound to one start/reload request.
 */
internal object TunRequestReadinessPolicy {
    fun isReady(
        configurationAccepted: Boolean,
        descriptorOwned: Boolean,
        descriptorRequestToken: Long?,
        requestToken: Long
    ): Boolean = configurationAccepted && descriptorOwned && descriptorRequestToken == requestToken
}
