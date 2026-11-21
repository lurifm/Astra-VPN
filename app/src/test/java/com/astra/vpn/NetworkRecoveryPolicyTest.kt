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
        // The caller passes true only for a validated, unsuspended physical network.
        assertFalse(NetworkRecoveryPolicy.shouldProbeTunnel(false))
    }
}
