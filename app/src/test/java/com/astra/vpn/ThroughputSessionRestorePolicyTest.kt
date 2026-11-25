package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputSessionRestorePolicyTest {
    @Test
    fun acceptsRecentSessionWithinRecoveryWindow() {
        assertTrue(ThroughputSessionRestorePolicy.canRecover(100_000L, 130_000L))
        assertTrue(ThroughputSessionRestorePolicy.canRecover(100_000L, 135_000L))
    }

    @Test
    fun rejectsExpiredSession() {
        assertFalse(ThroughputSessionRestorePolicy.canRecover(100_000L, 135_001L))
    }

    @Test
    fun rejectsFutureTimestampAndClockRollback() {
        assertFalse(ThroughputSessionRestorePolicy.canRecover(100_001L, 100_000L))
    }

    @Test
    fun rejectsMissingTimestamp() {
        assertFalse(ThroughputSessionRestorePolicy.canRecover(0L, 135_001L))
    }
}
