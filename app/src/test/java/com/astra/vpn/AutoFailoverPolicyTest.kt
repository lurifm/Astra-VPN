package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFailoverPolicyTest {
    @Test fun doesNotSwitchAfterOneOrTwoFailures() {
        assertFalse(AutoFailoverPolicy.shouldSwitch(1, 0L, 120_000L))
        assertFalse(AutoFailoverPolicy.shouldSwitch(2, 0L, 120_000L))
    }

    @Test fun switchesAfterThreeFailuresAndCooldown() {
        assertTrue(AutoFailoverPolicy.shouldSwitch(3, 0L, 60_000L))
    }

    @Test fun observesCooldownBetweenSwitches() {
        assertFalse(AutoFailoverPolicy.shouldSwitch(3, 50_000L, 109_999L))
        assertTrue(AutoFailoverPolicy.shouldSwitch(3, 50_000L, 110_000L))
    }

    @Test fun ignoresClockMovingBackwards() {
        assertFalse(AutoFailoverPolicy.shouldSwitch(4, 100_000L, 99_000L))
    }
}
