package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRecoveryPolicyTest {
    @Test
    fun consentCheckReturnsTrueOnlyWhenPlatformConfirmsGrant() {
        assertEquals(true, VpnRecoveryPolicy.isVpnConsentGranted { true })
        assertEquals(false, VpnRecoveryPolicy.isVpnConsentGranted { false })
    }

    @Test
    fun consentCheckFailsClosedAndReportsPlatformException() {
        val failure = SecurityException("permission state unavailable")
        var reported: Throwable? = null
        val granted = VpnRecoveryPolicy.isVpnConsentGranted(
            check = { throw failure },
            onFailure = { reported = it }
        )
        assertEquals(false, granted)
        assertEquals(failure, reported)
    }

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

    @Test
    fun explicitDisconnectHasPriorityOverMissingSavedProfileAndConsent() {
        assertEquals(
            VpnRecoveryDecision.DO_NOT_START,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.SYSTEM_PROCESS_RESTART,
                recoveryEnabled = true,
                userRequestedDisconnect = true,
                savedProfileAvailable = false,
                vpnConsentGranted = false
            )
        )
    }

    @Test
    fun disabledRecoveryDoesNotAskForVpnConsent() {
        assertEquals(
            VpnRecoveryDecision.DO_NOT_START,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.SYSTEM_PROCESS_RESTART,
                recoveryEnabled = false,
                userRequestedDisconnect = false,
                savedProfileAvailable = true,
                vpnConsentGranted = false
            )
        )
    }

    @Test
    fun explicitNewUserRequestSupersedesStaleDisconnectMarker() {
        assertEquals(
            VpnRecoveryDecision.START,
            VpnRecoveryPolicy.decide(
                VpnRecoveryTrigger.USER_REQUEST,
                recoveryEnabled = false,
                userRequestedDisconnect = true,
                savedProfileAvailable = true,
                vpnConsentGranted = false
            )
        )
    }

    @Test
    fun recoveryUpdateKeepsServiceStickyWhenEnabledAndNotStopping() {
        assertEquals(true, VpnRecoveryPolicy.shouldKeepServiceStickyForUpdate(true, false))
    }

    @Test
    fun recoveryUpdateDoesNotKeepServiceStickyWhenDisabled() {
        assertEquals(false, VpnRecoveryPolicy.shouldKeepServiceStickyForUpdate(false, false))
    }

    @Test
    fun recoveryUpdateDoesNotResurrectServiceAfterStopStarted() {
        assertEquals(true, VpnRecoveryPolicy.shouldKeepServiceStickyForUpdate(true, true).not())
    }

    @Test
    fun disabledRecoveryWhileStoppingRemainsNotSticky() {
        assertEquals(false, VpnRecoveryPolicy.shouldKeepServiceStickyForUpdate(false, true))
    }
}
