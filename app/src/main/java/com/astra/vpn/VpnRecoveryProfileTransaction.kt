package com.astra.vpn

/**
 * Keeps the last known-good recovery profile until a replacement VPN TUN
 * has actually been created. A failed or cancelled start must not overwrite it.
 */
internal data class VpnRecoveryProfileUpdate(val profileLink: String?)

internal class VpnRecoveryProfileTransaction {
    private var pending: VpnRecoveryProfileUpdate? = null

    @Synchronized
    fun stage(profileLink: String?) {
        pending = VpnRecoveryProfileUpdate(profileLink)
    }

    @Synchronized
    fun commit(): VpnRecoveryProfileUpdate? {
        val update = pending ?: return null
        pending = null
        return update
    }

    @Synchronized
    fun cancel() {
        pending = null
    }

    @Synchronized
    fun hasPendingUpdate(): Boolean = pending != null
}
