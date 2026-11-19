package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputDropFailoverPolicyTest {
    @Test fun requiresFiftyPercentDropForTenSeconds() {
        val p = ThroughputDropFailoverPolicy()
        assertFalse(p.shouldFailover(100.0, 60.0, 500L))
        assertFalse(p.shouldFailover(100.0, 50.0, 1_000L))
        assertFalse(p.shouldFailover(100.0, 50.0, 10_999L))
        assertTrue(p.shouldFailover(100.0, 49.0, 11_000L))
    }

    @Test fun recoveryResetsTimer() {
        val p = ThroughputDropFailoverPolicy()
        assertFalse(p.shouldFailover(100.0, 40.0, 1_000L))
        assertFalse(p.shouldFailover(100.0, 90.0, 8_000L))
        assertFalse(p.shouldFailover(100.0, 40.0, 9_000L))
        assertTrue(p.shouldFailover(100.0, 40.0, 19_000L))
    }

    @Test fun enforcesSixtySecondCooldown() {
        val p = ThroughputDropFailoverPolicy()
        assertFalse(p.shouldFailover(100.0, 40.0, 1_000L))
        assertTrue(p.shouldFailover(100.0, 40.0, 11_000L))
        assertFalse(p.shouldFailover(100.0, 40.0, 12_000L))
        assertFalse(p.shouldFailover(100.0, 40.0, 71_000L))
        assertTrue(p.shouldFailover(100.0, 40.0, 81_000L))
    }

    @Test fun invalidSampleClearsPendingDrop() {
        val p = ThroughputDropFailoverPolicy()
        assertFalse(p.shouldFailover(100.0, 40.0, 1_000L))
        assertFalse(p.shouldFailover(100.0, Double.NaN, 5_000L))
        assertFalse(p.shouldFailover(100.0, 40.0, 6_000L))
        assertTrue(p.shouldFailover(100.0, 40.0, 16_000L))
    }
}
