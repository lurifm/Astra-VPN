package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficUsageInsightsTest {
    @Test fun combinedTotalsSaturateInsteadOfWrappingToZero() {
        assertEquals(Long.MAX_VALUE, TrafficUsageInsights.Totals(Long.MAX_VALUE, Long.MAX_VALUE).combinedBytes)
    }
    @Test fun comparesAdjacentPeriods() {
        assertEquals("На 100% больше, чем за предыдущие 24 часа", TrafficUsageInsights.comparison(
            TrafficUsageInsights.Totals(200L, 0L), TrafficUsageInsights.Totals(100L, 0L)))
    }
    @Test fun handlesMissingBaseline() {
        assertEquals("Нет данных для сравнения со вчера", TrafficUsageInsights.comparison(
            TrafficUsageInsights.Totals(100L, 0L), TrafficUsageInsights.Totals(0L, 0L)))
    }
    @Test fun warnsOnOneGiBDailyUsage() {
        assertEquals("Высокий расход: за сутки использовано не менее 1 ГБ.", TrafficUsageInsights.warning(
            TrafficUsageInsights.Totals(1024L * 1024L * 1024L, 0L), TrafficUsageInsights.Totals(0L, 0L)))
    }
    @Test fun warnsOnStrongIncrease() {
        assertEquals("Расход заметно вырос: более чем вдвое и минимум на 100 МБ.", TrafficUsageInsights.warning(
            TrafficUsageInsights.Totals(250L * 1024L * 1024L, 0L), TrafficUsageInsights.Totals(100L * 1024L * 1024L, 0L)))
    }
    @Test fun ignoresSmallIncrease() {
        assertNull(TrafficUsageInsights.warning(
            TrafficUsageInsights.Totals(150L * 1024L * 1024L, 0L), TrafficUsageInsights.Totals(100L * 1024L * 1024L, 0L)))
    }
}
