package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTrafficTrendTest {
    @Test fun reportsGrowthAgainstPreviousDay() {
        assertEquals("↑ +100% ко вчера", AppTrafficTrend.describe(200L, 100L, 1L))
    }

    @Test fun reportsDecreaseAgainstPreviousDay() {
        assertEquals("↓ −50% ко вчера", AppTrafficTrend.describe(50L, 100L, 1L))
    }

    @Test fun skipsTrendWhenPreviousDayIsEmpty() {
        assertNull(AppTrafficTrend.describe(200L, 0L, 1L))
    }

    @Test fun skipsSmallChanges() {
        assertNull(AppTrafficTrend.describe(120L, 100L, 50L))
    }

    @Test fun aggregatesAllAppsForEachDay() {
        val days = listOf(
            AppTrafficStatsReader.DailySnapshot(20261117, "Вт", listOf(
                AppTrafficStatsReader.AppTraffic(1, "A", 100L, 20L),
                AppTrafficStatsReader.AppTraffic(2, "B", 50L, 10L)
            )),
            AppTrafficStatsReader.DailySnapshot(20261118, "Ср", listOf(
                AppTrafficStatsReader.AppTraffic(1, "A", 200L, 40L)
            ))
        )
        assertEquals(listOf(180L, 240L), AppTrafficTrend.dailyTotals(days))
    }
    @Test fun safeSumSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, AppTrafficTrend.safeSum(listOf(Long.MAX_VALUE, 1L)))
        assertEquals(15L, AppTrafficTrend.safeSum(listOf(10L, 5L)))
        assertEquals(10L, AppTrafficTrend.safeSum(listOf(-5L, 10L)))
    }

}
