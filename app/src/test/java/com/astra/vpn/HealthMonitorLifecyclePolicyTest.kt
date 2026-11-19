package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMonitorLifecyclePolicyTest {
    @Test fun schedulesOnlyWhenServiceAndTunAreActive() {
        assertTrue(HealthMonitorLifecyclePolicy.shouldScheduleTick(false, true))
        assertFalse(HealthMonitorLifecyclePolicy.shouldScheduleTick(true, true))
        assertFalse(HealthMonitorLifecyclePolicy.shouldScheduleTick(false, false))
        assertFalse(HealthMonitorLifecyclePolicy.shouldScheduleTick(true, false))
    }
}
