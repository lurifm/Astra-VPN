package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficForecastTest {
    @Test fun projectsLinearDailyUsage() {
        assertEquals(200L, TrafficForecast.projectedDailyBytes(100L, 43_200_000L, 86_400_000L))
    }
    @Test fun rejectsInvalidInputs() {
        assertNull(TrafficForecast.projectedDailyBytes(-1L, 100L))
        assertNull(TrafficForecast.projectedDailyBytes(10L, 0L))
    }
}
