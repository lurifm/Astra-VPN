package com.astra.vpn

/**
 * Pure decision policy for opt-in VPN recovery.
 *
 * This policy deliberately does not start Android components itself. Callers
 * must still check VpnService.prepare() and obey Android background-start rules.
 */
enum class VpnRecoveryTrigger {
    USER_REQUEST,
    SYSTEM_PROCESS_RESTART,
    DEVICE_BOOT,
    FORCE_STOP
}

enum class VpnRecoveryDecision {
    START,
    DO_NOT_START,
    REQUIRE_USER_ACTION,
    REQUIRE_VPN_CONSENT
}

object VpnRecoveryPolicy {
    /**
     * A stale recovery-settings command must not resurrect a service after
     * an explicit stop has already put its lifecycle into the stopping state.
     */
    fun shouldKeepServiceStickyForUpdate(recoveryEnabled: Boolean, stopping: Boolean): Boolean =
        recoveryEnabled && !stopping

    fun decide(
        trigger: VpnRecoveryTrigger,
        recoveryEnabled: Boolean,
        userRequestedDisconnect: Boolean,
        savedProfileAvailable: Boolean,
        vpnConsentGranted: Boolean
    ): VpnRecoveryDecision {
        if (trigger == VpnRecoveryTrigger.USER_REQUEST) {
            return if (savedProfileAvailable) VpnRecoveryDecision.START
            else VpnRecoveryDecision.DO_NOT_START
        }

        // Android keeps a force-stopped package stopped until the user launches it.
        if (trigger == VpnRecoveryTrigger.FORCE_STOP) {
            return VpnRecoveryDecision.REQUIRE_USER_ACTION
        }

        // Boot recovery is intentionally gated until a dedicated receiver and
        // target-API foreground-service behavior are implemented and verified.
        if (trigger == VpnRecoveryTrigger.DEVICE_BOOT) {
            return VpnRecoveryDecision.REQUIRE_USER_ACTION
        }

        if (!recoveryEnabled || userRequestedDisconnect || !savedProfileAvailable) {
            return VpnRecoveryDecision.DO_NOT_START
        }
        if (!vpnConsentGranted) return VpnRecoveryDecision.REQUIRE_VPN_CONSENT
        return VpnRecoveryDecision.START
    }
}
