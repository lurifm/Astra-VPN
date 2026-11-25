package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficForecastTest {
    @Test
    fun excludesTodayAndAveragesCompletedDays() {
        val history = DailyTrafficHistory()
        history.record(20261122, 100L, 0L)
        history.record(20261123, 200L, 50L)
        history.record(20261124, 900L, 100L)

        val result = TrafficForecast.estimate(history.entries(), todayKey = 20261124)

        assertEquals(TrafficForecast.Result(175L, 2), result)
    }

    @Test
    fun requiresAtLeastTwoCompletedDays() {
        val history = DailyTrafficHistory()
        history.record(20261123, 100L, 20L)
        history.record(20261124, 500L, 30L)

        assertNull(TrafficForecast.estimate(history.entries(), todayKey = 20261124))
    }

    @Test
    fun usesOnlySixMostRecentCompletedDays() {
        val history = DailyTrafficHistory()
        (20261117..20261123).forEach { day ->
            history.record(day, (day - 20261117).toLong() * 100L, 0L)
        }

        val result = TrafficForecast.estimate(history.entries(), todayKey = 20261124)

        // The oldest completed day is excluded; the six newest totals are 100..600.
        assertEquals(TrafficForecast.Result(350L, 6), result)
    }

    @Test
    fun averagesSaturatedDailyTotalsWithoutOverflow() {
        val history = DailyTrafficHistory()
        history.record(20261122, Long.MAX_VALUE, Long.MAX_VALUE)
        history.record(20261123, Long.MAX_VALUE, 1L)

        val result = TrafficForecast.estimate(history.entries(), todayKey = 20261124)

        assertEquals(TrafficForecast.Result(Long.MAX_VALUE, 2), result)
    }

    @Test
    fun ignoresNegativeTrafficValuesWhenEstimating() {
        val entries = listOf(
            DailyTrafficHistory.Entry(20261122, DailyTrafficHistory.Totals(-100L, 50L)),
            DailyTrafficHistory.Entry(20261123, DailyTrafficHistory.Totals(150L, -25L))
        )

        val result = TrafficForecast.estimate(entries, todayKey = 20261124)

        assertEquals(TrafficForecast.Result(125L, 2), result)
    }
}
