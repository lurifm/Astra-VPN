package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryAwarePollingPolicyTest {
    @Test fun normalModeUsesResponsiveInterval() {
        assertEquals(1_000L, BatteryAwarePollingPolicy.interval(false))
    }
    @Test fun powerSaveModeReducesPollingFrequency() {
        assertEquals(5_000L, BatteryAwarePollingPolicy.interval(true))
    }
    @Test fun subscriptionRefreshSlowsDownInPowerSaveMode() {
        assertEquals(60_000L, BatteryAwarePollingPolicy.subscriptionInterval(false))
        assertEquals(300_000L, BatteryAwarePollingPolicy.subscriptionInterval(true))
    }
}
