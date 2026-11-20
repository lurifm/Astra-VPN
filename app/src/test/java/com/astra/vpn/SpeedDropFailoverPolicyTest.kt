package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedDropFailoverPolicyTest {
    @Test
    fun requiresSustainedDropBelowHalfOfRecentPeak() {
        val policy = SpeedDropFailoverPolicy()
        assertFalse(policy.update(10.0, 1_000L, true))
        assertFalse(policy.update(4.0, 2_000L, true))
        assertFalse(policy.update(4.0, 11_999L, true))
        assertTrue(policy.update(4.0, 12_000L, true))
    }

    @Test
    fun ignoresIdleTrafficAndResetsTheDropTimer() {
        val policy = SpeedDropFailoverPolicy()
        assertFalse(policy.update(10.0, 1_000L, true))
        assertFalse(policy.update(4.0, 2_000L, true))
        assertFalse(policy.update(0.01, 7_000L, true))
        assertFalse(policy.update(4.0, 8_000L, true))
        assertFalse(policy.update(4.0, 17_999L, true))
        assertTrue(policy.update(4.0, 18_000L, true))
    }

    @Test
    fun doesNotTriggerWhenVpnIsDisconnected() {
        val policy = SpeedDropFailoverPolicy()
        assertFalse(policy.update(10.0, 1_000L, false))
        assertFalse(policy.update(4.0, 20_000L, true))
    }

    @Test
    fun appliesCooldownAfterFailover() {
        val policy = SpeedDropFailoverPolicy()
        policy.update(10.0, 1_000L, true)
        assertFalse(policy.update(4.0, 2_000L, true))
        assertTrue(policy.update(4.0, 12_000L, true))
        policy.update(10.0, 13_000L, true)
        assertFalse(policy.update(4.0, 14_000L, true))
        assertFalse(policy.update(4.0, 24_000L, true))
        assertFalse(policy.update(4.0, 132_000L, true))
        assertTrue(policy.update(4.0, 142_000L, true))
    }
}
