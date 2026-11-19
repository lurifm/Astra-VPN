package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMonitorLifecyclePolicyTest {
    @Test fun startsProbeOnlyForActiveServiceWithTunAndVpnNetwork() {
        assertTrue(HealthMonitorLifecyclePolicy.shouldStartProbe(false, true, true, false))
        assertFalse(HealthMonitorLifecyclePolicy.shouldStartProbe(true, true, true, false))
        assertFalse(HealthMonitorLifecyclePolicy.shouldStartProbe(false, false, true, false))
        assertFalse(HealthMonitorLifecyclePolicy.shouldStartProbe(false, true, false, false))
        assertFalse(HealthMonitorLifecyclePolicy.shouldStartProbe(false, true, true, true))
    }

    @Test fun schedulesOnlyWhenServiceAndTunAreActive() {
        assertTrue(HealthMonitorLifecyclePolicy.shouldScheduleTick(false, true))
        assertFalse(HealthMonitorLifecyclePolicy.shouldScheduleTick(true, true))
        assertFalse(HealthMonitorLifecyclePolicy.shouldScheduleTick(false, false))
        assertFalse(HealthMonitorLifecyclePolicy.shouldScheduleTick(true, false))
    }
}
