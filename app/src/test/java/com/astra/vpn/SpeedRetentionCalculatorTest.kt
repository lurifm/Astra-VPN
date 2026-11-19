package com.astra.vpn

import org.junit.Assert.*
import org.junit.Test

class SpeedRetentionCalculatorTest {
    @Test fun calculatesRetainedSpeed() {
        assertEquals(85, SpeedRetentionCalculator.percentage(100.0, 85.0))
        assertEquals(true, SpeedRetentionCalculator.meetsTarget(100.0, 85.0))
        assertEquals(false, SpeedRetentionCalculator.meetsTarget(100.0, 79.9))
    }

    @Test fun rejectsInvalidMeasurements() {
        assertNull(SpeedRetentionCalculator.percentage(0.0, 50.0))
        assertNull(SpeedRetentionCalculator.percentage(100.0, Double.NaN))
        assertNull(SpeedRetentionCalculator.meetsTarget(100.0, -1.0))
    }
}
