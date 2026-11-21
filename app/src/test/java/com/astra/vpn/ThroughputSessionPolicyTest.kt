package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputSessionPolicyTest {
    @Test
    fun onlyMatchingNonBlankSessionCanStopProbe() {
        assertTrue(ThroughputSessionPolicy.shouldStop("session-1", "session-1"))
        assertFalse(ThroughputSessionPolicy.shouldStop("session-2", "session-1"))
        assertFalse(ThroughputSessionPolicy.shouldStop("session-1", null))
        assertFalse(ThroughputSessionPolicy.shouldStop(null, "session-1"))
        assertFalse(ThroughputSessionPolicy.shouldStop("", ""))
    }
}
