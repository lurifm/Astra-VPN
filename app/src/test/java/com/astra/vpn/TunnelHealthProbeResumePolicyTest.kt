package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthProbeResumePolicyTest {
    @Test fun staleProbeIsRescheduledWhenActivityReturnsAndVpnIsConnected() {
        assertTrue(
            TunnelHealthProbeResumePolicy.shouldReschedule(
                activityActive = true,
                connected = true,
                generationCurrent = false
            )
        )
    }

    @Test fun currentProbeIsNotDuplicated() {
        assertFalse(TunnelHealthProbeResumePolicy.shouldReschedule(true, true, true))
    }

    @Test fun backgroundActivityDoesNotScheduleUiHealthProbe() {
        assertFalse(TunnelHealthProbeResumePolicy.shouldReschedule(false, true, false))
    }

    @Test fun disconnectedTunnelDoesNotScheduleHealthProbe() {
        assertFalse(TunnelHealthProbeResumePolicy.shouldReschedule(true, false, false))
    }
}
