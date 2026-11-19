package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedInsightsTest {
    @Test
    fun diagnosticsExplainsCaptivePortalAndTunnelFailure() {
        val advice = DiagnosticAdvisor.recommendations(
            DiagnosticAdvisor.Facts(
                hasNetwork = true,
                hasInternetCapability = true,
                validated = false,
                vpnActive = true,
                tunnelInternetOk = false,
                captivePortal = true,
                dnsServerCount = 1
            )
        )
        assertTrue(advice.any { it.contains("авторизации") })
        assertTrue(advice.any { it.contains("другой сервер") })
    }

    @Test
    fun trafficForecastUsesCompletedDaysAndRequiresTwoSamples() {
        val one = listOf(
            DailyTrafficHistory.Entry(20261118, DailyTrafficHistory.Totals(100L, 50L))
        )
        assertNull(TrafficForecast.estimate(one, 20261119))
        val two = one + DailyTrafficHistory.Entry(
            20261117, DailyTrafficHistory.Totals(200L, 100L)
        )
        val result = TrafficForecast.estimate(two, 20261119)
        assertNotNull(result)
        assertEquals(225L, result!!.averageDailyBytes)
        assertEquals(2, result.sampleDays)
    }

    @Test
    fun reliabilityScoreIsBoundedAndReportsConfidence() {
        val events = listOf(
            ConnectionHistoryStore.Entry(3L, "error", "A", "VLESS"),
            ConnectionHistoryStore.Entry(2L, "connected", "A", "VLESS"),
            ConnectionHistoryStore.Entry(1L, "connected", "B", "TUIC")
        )
        val score = ReliabilityScore.calculate(events)
        assertNotNull(score)
        assertEquals(82, score!!.score)
        assertEquals("предварительная", score.confidence)
    }
}
