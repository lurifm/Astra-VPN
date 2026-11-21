package com.astra.vpn

/**
 * A TUN callback alone is not proof that libbox accepted the configuration.
 * Publish CONNECTED and commit recovery state only after both conditions hold.
 */
internal object TunActivationReadinessPolicy {
    fun isReady(configurationAccepted: Boolean, descriptorOwned: Boolean): Boolean =
        configurationAccepted && descriptorOwned
}
