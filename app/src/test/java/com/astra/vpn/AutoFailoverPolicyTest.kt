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

    @Test fun ignoresClockMovingBackwards() {
        assertFalse(AutoFailoverPolicy.shouldSwitch(4, 100_000L, 99_000L))
    }
}
