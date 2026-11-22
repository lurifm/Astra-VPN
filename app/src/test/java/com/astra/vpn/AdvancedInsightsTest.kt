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
        assertEquals(67, score!!.score)
        assertEquals("предварительная", score.confidence)
    }

    @Test
    fun reliabilityPenaltyScalesWithEvidenceAndNeverExceedsCap() {
        val sparse = ReliabilityScore.Result(0, sessions = 0, failures = 3, confidence = "предварительная")
        val mature = ReliabilityScore.Result(0, sessions = 0, failures = 20, confidence = "высокая")
        assertTrue(ReliabilityScore.selectionPenaltyMs(sparse) < ReliabilityScore.selectionPenaltyMs(mature))
        assertEquals(60L, ReliabilityScore.selectionPenaltyMs(mature))
        assertEquals(0L, ReliabilityScore.selectionPenaltyMs(mature, maxPenaltyMs = 0L))
    }

    @Test
    fun perfectReliabilityHasNoSelectionPenalty() {
        val perfect = ReliabilityScore.Result(100, sessions = 20, failures = 0, confidence = "высокая")
        assertEquals(0L, ReliabilityScore.selectionPenaltyMs(perfect))
    }


    @Test
    fun validatedRecoveryClearsOlderErrorsFromReliabilityHistory() {
        val events = listOf(
            ConnectionHistoryStore.Entry(300L, "connected", "A", "VLESS"),
            ConnectionHistoryStore.Entry(200L, "error", "A", "VLESS"),
            ConnectionHistoryStore.Entry(100L, "error", "A", "VLESS")
        )
        val filtered = ReliabilityScore.outcomesAfterLatestSuccess(events)
        assertEquals(1, filtered.size)
        assertEquals("connected", filtered.single().state)
        assertEquals(100, ReliabilityScore.calculate(filtered + listOf(
            ConnectionHistoryStore.Entry(250L, "connected", "A", "VLESS"),
            ConnectionHistoryStore.Entry(275L, "connected", "A", "VLESS")
        ))!!.score)
    }

    @Test
    fun failureAfterRecoveryRemainsInReliabilityHistory() {
        val events = listOf(
            ConnectionHistoryStore.Entry(400L, "error", "A", "VLESS"),
            ConnectionHistoryStore.Entry(300L, "connected", "A", "VLESS"),
            ConnectionHistoryStore.Entry(200L, "error", "A", "VLESS"),
            ConnectionHistoryStore.Entry(100L, "connected", "A", "VLESS")
        )
        val filtered = ReliabilityScore.outcomesAfterLatestSuccess(events)
        assertEquals(listOf("error", "connected"), filtered.map { it.state })
    }

}
