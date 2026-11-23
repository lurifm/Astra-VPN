package com.astra.vpn

import org.junit.Assert.assertEquals
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

    @Test fun failureCountIncrementsNormally() {
        assertTrue(AutoFailoverPolicy.nextFailureCount(0) == 1)
        assertTrue(AutoFailoverPolicy.nextFailureCount(2) == 3)
    }

    @Test fun failureCountDoesNotOverflow() {
        assertTrue(AutoFailoverPolicy.nextFailureCount(Int.MAX_VALUE) == Int.MAX_VALUE)
    }

    @Test fun negativeFailureCountRecoversToOne() {
        assertTrue(AutoFailoverPolicy.nextFailureCount(-7) == 1)
    }

    @Test fun cooldownComparisonDoesNotOverflowForExtremeTimestamps() {
        assertFalse(AutoFailoverPolicy.shouldSwitch(3, Long.MIN_VALUE, 0L))
        assertFalse(AutoFailoverPolicy.shouldSwitch(3, Long.MAX_VALUE - 1L, Long.MAX_VALUE))
    }

    @Test fun rejectsNegativeTimestamps() {
        assertFalse(AutoFailoverPolicy.shouldSwitch(3, -1L, 100_000L))
        assertFalse(AutoFailoverPolicy.shouldSwitch(3, 0L, -1L))
    }

    @Test
    fun firstAutomaticSwitchDoesNotWaitForDeviceUptime() {
        assertTrue(AutoFailoverPolicy.cooldownElapsed(null, 0L, 60_000L))
        assertTrue(AutoFailoverPolicy.shouldSwitch(3, null, 0L))
        assertFalse(AutoFailoverPolicy.shouldSwitch(2, null, 0L))
        assertFalse(AutoFailoverPolicy.cooldownElapsed(null, -1L, 60_000L))
        assertFalse(AutoFailoverPolicy.cooldownElapsed(null, 0L, 0L))
    }

    @Test fun ignoresClockMovingBackwards() {
        assertFalse(AutoFailoverPolicy.shouldSwitch(4, 100_000L, 99_000L))
    }
    @Test
    fun cooldownGrowsWithRecentAutomaticHopsAndIsCapped() {
        assertEquals(60_000L, AutoFailoverPolicy.cooldownMs(0))
        assertEquals(60_000L, AutoFailoverPolicy.cooldownMs(1))
        assertEquals(120_000L, AutoFailoverPolicy.cooldownMs(2))
        assertEquals(180_000L, AutoFailoverPolicy.cooldownMs(3))
        assertEquals(AutoFailoverPolicy.MAX_COOLDOWN_MS, AutoFailoverPolicy.cooldownMs(100))
        assertEquals(60_000L, AutoFailoverPolicy.cooldownMs(-1))
    }

    @Test
    fun adaptiveCooldownIsAppliedToSwitchDecision() {
        assertFalse(
            AutoFailoverPolicy.shouldSwitch(
                AutoFailoverPolicy.REQUIRED_FAILURES, 1_000L, 90_000L,
                AutoFailoverPolicy.cooldownMs(2)
            )
        )
        assertTrue(
            AutoFailoverPolicy.shouldSwitch(
                AutoFailoverPolicy.REQUIRED_FAILURES, 1_000L, 121_000L,
                AutoFailoverPolicy.cooldownMs(2)
            )
        )
    }

    @Test
    fun sharedCooldownGateRejectsEarlyAndInvalidSwitches() {
        assertFalse(AutoFailoverPolicy.cooldownElapsed(10_000L, 129_999L, 120_000L))
        assertTrue(AutoFailoverPolicy.cooldownElapsed(10_000L, 130_000L, 120_000L))
        assertFalse(AutoFailoverPolicy.cooldownElapsed(-1L, 500_000L, 1L))
        assertFalse(AutoFailoverPolicy.cooldownElapsed(0L, 500_000L, 0L))
        assertFalse(AutoFailoverPolicy.cooldownElapsed(0L, -1L, 1L))
        assertFalse(AutoFailoverPolicy.cooldownElapsed(Long.MAX_VALUE, Long.MAX_VALUE, 1L))
    }

}
