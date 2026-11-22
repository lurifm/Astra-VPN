package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyQualityPolicyTest {
    @Test
    fun usesConsistentQualityBandsAtBoundaries() {
        assertEquals(0, LatencyQualityPolicy.band(null))
        assertEquals(0, LatencyQualityPolicy.band(0L))
        assertEquals(3, LatencyQualityPolicy.band(1L))
        assertEquals(3, LatencyQualityPolicy.band(100L))
        assertEquals(2, LatencyQualityPolicy.band(101L))
        assertEquals(2, LatencyQualityPolicy.band(250L))
        assertEquals(1, LatencyQualityPolicy.band(251L))
    }

    @Test
    fun exposesHumanReadableQualityLabels() {
        assertEquals("отлично", LatencyQualityPolicy.label(80L))
        assertEquals("средне", LatencyQualityPolicy.label(180L))
        assertEquals("высоко", LatencyQualityPolicy.label(400L))
    }
}
