package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryPolicyTest {
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
