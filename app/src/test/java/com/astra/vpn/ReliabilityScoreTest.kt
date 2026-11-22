package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityScoreTest {
    private fun event(timestamp: Long, state: String) =
        ConnectionHistoryStore.Entry(timestamp, state, "Node", "VLESS")

    @Test
    fun scoreUsesObservedSuccessRatio() {
        val result = ReliabilityScore.calculate(
            listOf(event(4L, "connected"), event(3L, "connected"), event(2L, "error"))
        )
        assertEquals(67, result?.score)
        assertEquals(2, result?.sessions)
        assertEquals(1, result?.failures)
        assertEquals("предварительная", result?.confidence)
    }

    @Test
    fun allFailuresProduceZeroAndAllSuccessesProduceOneHundred() {
        assertEquals(
            0,
            ReliabilityScore.calculate(listOf(event(3L, "error"), event(2L, "error"), event(1L, "error")))?.score
        )
        assertEquals(
            100,
            ReliabilityScore.calculate(listOf(event(3L, "connected"), event(2L, "connected"), event(1L, "connected")))?.score
        )
    }

    @Test
    fun ignoresInvalidAndUnrelatedEventsBeforeApplyingSampleLimit() {
        val events = listOf(
            event(0L, "connected"),
            event(5L, "connecting"),
            event(4L, "unknown"),
            event(3L, "connected"),
            event(2L, "error"),
            event(1L, "connected")
        )
        val result = ReliabilityScore.calculate(events)
        assertEquals(67, result?.score)
        assertEquals(2, result?.sessions)
        assertEquals(1, result?.failures)
    }

    @Test
    fun requiresAtLeastThreeValidOutcomes() {
        assertNull(ReliabilityScore.calculate(listOf(event(2L, "connected"), event(1L, "error"))))
    }

    @Test
    fun confidenceReflectsValidSampleSize() {
        val medium = (1..8).map { event(it.toLong(), if (it % 2 == 0) "connected" else "error") }
        val high = (1..20).map { event(it.toLong(), "connected") }
        assertEquals("средняя", ReliabilityScore.calculate(medium)?.confidence)
        assertEquals("высокая", ReliabilityScore.calculate(high)?.confidence)
    }
    @Test
    fun identityNormalizationIsSharedAcrossProtocolAliasesAndWhitespace() {
        assertEquals("node|hysteria2", ReliabilityScore.identity("  NODE  ", " HY2 "))
        assertEquals("node|hysteria2", ReliabilityScore.identity("node", "Hysteria2"))
        assertEquals("node|vless", ReliabilityScore.identity("Node", " VLESS "))
    }

    @Test
    fun profileFingerprintSeparatesServersWithSameDisplayNameAndProtocol() {
        val first = event(3L, "error").copy(serverId = ProfileIdentity.of("vless://one@a.example:443"))
        val second = event(2L, "connected").copy(serverId = ProfileIdentity.of("vless://two@b.example:443"))
        assertEquals("profile:${ProfileIdentity.of("vless://one@a.example:443")}", ReliabilityScore.identity(first))
        assertEquals("profile:${ProfileIdentity.of("vless://two@b.example:443")}", ReliabilityScore.identity(second))
        val penalties = ReliabilityScore.selectionPenaltiesByIdentity(
            listOf(first, event(2L, "error").copy(serverId = first.serverId), event(1L, "error").copy(serverId = first.serverId))
        )
        assertTrue(penalties.containsKey(ReliabilityScore.identity(first)))
    }

    @Test
    fun recoveryKeepsOnlyLatestSuccessAndDropsEarlierOutcomes() {
        val events = listOf(
            event(6L, "connected"), event(5L, "connected"), event(4L, "error"),
            event(3L, "connected"), event(2L, "error"), event(1L, "connected")
        )
        val outcomes = ReliabilityScore.outcomesAfterLatestSuccess(events)
        assertEquals(listOf(6L), outcomes.map { it.timestamp })
        assertEquals(listOf("connected"), outcomes.map { it.state })
    }

}
