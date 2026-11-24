package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficForecastTest {
    @Test
    fun averagesCompletedDaysAndExcludesToday() {
        val result = TrafficForecast.estimate(
            listOf(
                DailyTrafficHistory.Entry(20261124, DailyTrafficHistory.Totals(9_000L, 9_000L)),
                DailyTrafficHistory.Entry(20261123, DailyTrafficHistory.Totals(100L, 50L)),
                DailyTrafficHistory.Entry(20261122, DailyTrafficHistory.Totals(200L, 50L))
            ),
            todayKey = 20261124
        )

        assertEquals(TrafficForecast.Result(200L, 2), result)
    }

    @Test
    fun saturatesCombinedDailyCountersAndAverage() {
        val result = TrafficForecast.estimate(
            listOf(
                DailyTrafficHistory.Entry(20261123, DailyTrafficHistory.Totals(Long.MAX_VALUE, Long.MAX_VALUE)),
                DailyTrafficHistory.Entry(20261122, DailyTrafficHistory.Totals(Long.MAX_VALUE, 1L))
            ),
            todayKey = 20261124
        )

        assertEquals(TrafficForecast.Result(Long.MAX_VALUE, 2), result)
    }

    @Test
    fun requiresAtLeastTwoCompletedDays() {
        val result = TrafficForecast.estimate(
            listOf(
                DailyTrafficHistory.Entry(20261124, DailyTrafficHistory.Totals(100L, 50L)),
                DailyTrafficHistory.Entry(20261123, DailyTrafficHistory.Totals(200L, 50L))
            ),
            todayKey = 20261124
        )

        assertNull(result)
    }

    @Test
    fun normalizesNegativeCountersToZero() {
        val result = TrafficForecast.estimate(
            listOf(
                DailyTrafficHistory.Entry(20261123, DailyTrafficHistory.Totals(-10L, -20L)),
                DailyTrafficHistory.Entry(20261122, DailyTrafficHistory.Totals(0L, 0L))
            ),
            todayKey = 20261124
        )

        assertEquals(TrafficForecast.Result(0L, 2), result)
    }
}
