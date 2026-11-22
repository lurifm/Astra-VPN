package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {
    @Test
    fun networkRetryBackoffIsBoundedAndStopsAfterThreeAttempts() {
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(0) == 1_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(1) == 2_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(2) == 4_000L)
        assertTrue(NetworkRecoveryPolicy.retryDelayMs(3) == null)
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

    @Test
    fun staleNetworkCallbackCannotInvalidateProbesOnNewerUnderlay() {
        assertTrue(NetworkRecoveryPolicy.isLatestNetworkChange(7L, 7L))
        assertFalse(NetworkRecoveryPolicy.isLatestNetworkChange(6L, 7L))
    }

    @Test
    fun handoverRetryIsBoundedToTwoAttempts() {
        assertTrue(NetworkRecoveryPolicy.shouldRetryAfterHandover(0))
        assertTrue(NetworkRecoveryPolicy.shouldRetryAfterHandover(1))
        assertFalse(NetworkRecoveryPolicy.shouldRetryAfterHandover(2))
        assertFalse(NetworkRecoveryPolicy.shouldRetryAfterHandover(3))
    }

    @Test
    fun prolongedUnderlayOutageDoesNotConsumeHandoverRetryBudget() {
        assertTrue(NetworkRecoveryPolicy.shouldRequeueAfterHandover(0, false))
        assertTrue(NetworkRecoveryPolicy.shouldRequeueAfterHandover(1, false))
        assertTrue(NetworkRecoveryPolicy.shouldRequeueAfterHandover(2, false))
        assertTrue(NetworkRecoveryPolicy.shouldRequeueAfterHandover(3, false))
    }

    @Test
    fun recoveredUnderlayRetriesOnlyWithinConfiguredBudget() {
        assertTrue(NetworkRecoveryPolicy.shouldRequeueAfterHandover(0, true))
        assertTrue(NetworkRecoveryPolicy.shouldRequeueAfterHandover(1, true))
        assertFalse(NetworkRecoveryPolicy.shouldRequeueAfterHandover(2, true))
    }

    @Test
    fun handoverKeepsNativeProbeSlotForFullCancellationSafetyWindow() {
        assertEquals(
            NativeUrlTestTimingPolicy.NATIVE_MAX_DURATION_MS +
                NativeUrlTestTimingPolicy.SAFETY_MARGIN_MS,
            NetworkRecoveryPolicy.probeHandoverNativeCooldownMs()
        )
    }

    @Test
    fun probeSocketBindingRejectsUnvalidatedAndVpnNetworks() {
        assertFalse(NetworkRecoveryPolicy.isProbeSocketNetworkEligible(false, true, false, true))
        assertFalse(NetworkRecoveryPolicy.isProbeSocketNetworkEligible(true, true, true, true))
        assertFalse(NetworkRecoveryPolicy.isProbeSocketNetworkEligible(false, true, true, false))
        assertFalse(NetworkRecoveryPolicy.isProbeSocketNetworkEligible(false, false, true, true))
        assertTrue(NetworkRecoveryPolicy.isProbeSocketNetworkEligible(false, true, true, true))
    }

    @Test
    fun readyPlatformNullResultDoesNotResurrectStaleCallbackNetwork() {
        assertEquals(
            null,
            NetworkRecoveryPolicy.chooseProbeFallback(
                platformReady = true,
                refreshedCandidate = null,
                callbackCandidate = "stale-network"
            )
        )
    }

    @Test
    fun callbackSnapshotIsOnlyUsedBeforePlatformAdapterIsReady() {
        assertEquals(
            "startup-network",
            NetworkRecoveryPolicy.chooseProbeFallback(
                platformReady = false,
                refreshedCandidate = null,
                callbackCandidate = "startup-network"
            )
        )
        assertEquals(
            "current-network",
            NetworkRecoveryPolicy.chooseProbeFallback(
                platformReady = true,
                refreshedCandidate = "current-network",
                callbackCandidate = "stale-network"
            )
        )
    }

    @Test
    fun callbackRejectsCaptivePortalAndSuspendedNetworks() {
        assertFalse(NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(false, true, false, true))
        assertFalse(NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(false, true, true, false))
    }

    @Test
    fun callbackRejectsVpnEvenWhenItReportsValidatedInternet() {
        assertFalse(NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(true, true, true, true))
    }
}
