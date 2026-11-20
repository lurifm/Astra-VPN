package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRecoveryPolicyTest {
    @Test
    fun systemRestartStartsOnlyWhenOptedInAndConsentIsGranted() {
        assertEquals(
            VpnRecoveryDecision.START,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.SYSTEM_PROCESS_RESTART,
                recoveryEnabled = true,
                userRequestedDisconnect = false,
                savedProfileAvailable = true,
                vpnConsentGranted = true
            )
        )
    }

    @Test
    fun systemRestartDoesNotStartWithoutOptIn() {
        assertEquals(
            VpnRecoveryDecision.DO_NOT_START,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.SYSTEM_PROCESS_RESTART,
                false, false, true, true
            )
        )
    }

    @Test
    fun explicitDisconnectWinsOverRecoveryPreference() {
        assertEquals(
            VpnRecoveryDecision.DO_NOT_START,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.SYSTEM_PROCESS_RESTART,
                true, true, true, true
            )
        )
    }

    @Test
    fun missingSavedProfilePreventsRecovery() {
        assertEquals(
            VpnRecoveryDecision.DO_NOT_START,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.SYSTEM_PROCESS_RESTART,
                true, false, false, true
            )
        )
    }

    @Test
    fun missingVpnConsentRequiresUserAction() {
        assertEquals(
            VpnRecoveryDecision.REQUIRE_VPN_CONSENT,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.SYSTEM_PROCESS_RESTART,
                true, false, true, false
            )
        )
    }

    @Test
    fun forceStopAlwaysRequiresManualLaunch() {
        assertEquals(
            VpnRecoveryDecision.REQUIRE_USER_ACTION,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.FORCE_STOP,
                true, false, true, true
            )
        )
    }

    @Test
    fun bootRecoveryRemainsDisabledUntilPlatformIntegrationExists() {
        assertEquals(
            VpnRecoveryDecision.REQUIRE_USER_ACTION,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.DEVICE_BOOT,
                true, false, true, true
            )
        )
    }

    @Test
    fun explicitUserRequestCanStartWithoutRecoveryOptIn() {
        assertEquals(
            VpnRecoveryDecision.START,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.USER_REQUEST,
                false, false, true, false
            )
        )
    }
}
