package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test
    fun networkRetryBackoffSurvivesLongHandoverAndRemainsBounded() {
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(0) == 1_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(1) == 2_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(2) == 4_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(3) == 8_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(4) == 16_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(5) == 30_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(6) == null)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(-1) == null)
    }

    @Test
    fun eitherBatterySaverUsesReducedMonitorPollingCadence() {
        assertTrue(NetworkRecoveryPolicy.monitorIntervalMs(false, false) == 1_000L)
        assertTrue(NetworkRecoveryPolicy.monitorIntervalMs(true, false) == 4_000L)
        assertTrue(NetworkRecoveryPolicy.monitorIntervalMs(false, true) == 4_000L)
        assertTrue(NetworkRecoveryPolicy.monitorIntervalMs(true, true) == 4_000L)
    }

    @Test
    fun physicalInternetLossPausesTunnelHealthProbes() {
        assertFalse(NetworkRecoveryPolicy.shouldProbeTunnel(false))
    }

    @Test
    fun validatedPhysicalInternetAllowsTunnelHealthProbes() {
        assertTrue(NetworkRecoveryPolicy.shouldProbeTunnel(true))
    }

    @Test
    fun unvalidatedOrCaptivePortalNetworkIsNotTreatedAsUsableUnderlay() {
        assertFalse(NetworkRecoveryPolicy.shouldProbeTunnel(false))
    }

    @Test
    fun failedAndroidUnderlayUpdateBlocksPublishingNewRoute() {
        assertFalse(NetworkRecoveryPolicy.shouldPublishUnderlay(true, true, false))
    }

    @Test
    fun successfulAndroidUnderlayUpdateAllowsPublishingNewRoute() {
        assertTrue(NetworkRecoveryPolicy.shouldPublishUnderlay(true, true, true))
    }

    @Test
    fun unchangedOrExternallyManagedUnderlayDoesNotRequireUpdate() {
        assertTrue(NetworkRecoveryPolicy.shouldPublishUnderlay(true, false, false))
        assertTrue(NetworkRecoveryPolicy.shouldPublishUnderlay(false, true, false))
    }

    @Test
    fun validatedPhysicalUnderlayIsAccepted() {
        assertTrue(NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(false, true, true, true))
    }

    @Test
    fun vpnNetworkIsNeverAcceptedAsPhysicalUnderlay() {
        assertFalse(NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(true, true, true, true))
    }

    @Test
    fun internetWithoutValidationIsNotEnoughToProbeTunnel() {
        assertFalse(NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(false, true, false, true))
    }

    @Test
    fun suspendedPhysicalNetworkIsNotAccepted() {
        assertFalse(NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(false, true, true, false))
    }

    @Test
    fun networkWithoutInternetCapabilityIsRejected() {
        assertFalse(NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(false, false, true, true))
    }
}
