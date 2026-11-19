package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFailureScorerTest {
    private fun entry(ageMs: Long, name: String = "Node A", state: String = "error", now: Long = 10L * ServerFailureScorer.DEFAULT_WINDOW_MS) =
        ConnectionHistoryStore.Entry(now - ageMs, state, name, "VLESS")

    @Test
    fun weightsRecentErrorsMoreHeavilyThanOlderErrors() {
        val now = 1_000_000_000L
        val result = ServerFailureScorer.weightedFailures(
            listOf(
                ConnectionHistoryStore.Entry(now - 60_000L, "error", "Node A", "VLESS"),
                ConnectionHistoryStore.Entry(now - 7L * 60L * 60L * 1_000L, "error", "Node A", "VLESS"),
                ConnectionHistoryStore.Entry(now - 2L * 24L * 60L * 60L * 1_000L, "error", "Node A", "VLESS")
            ),
            now
        )
        assertEquals(6, result["node a"])
    }

    @Test
    fun ignoresOldFutureInvalidAndNonErrorEntries() {
        val now = 1_000_000_000L
        val result = ServerFailureScorer.weightedFailures(
            listOf(
                ConnectionHistoryStore.Entry(now - ServerFailureScorer.DEFAULT_WINDOW_MS - 1L, "error", "Old", "VLESS"),
                ConnectionHistoryStore.Entry(now + 1L, "error", "Future", "VLESS"),
                ConnectionHistoryStore.Entry(-1L, "error", "Invalid", "VLESS"),
                ConnectionHistoryStore.Entry(now, "connected", "Connected", "VLESS")
            ),
            now
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun normalizesNamesAndKeepsServersSeparate() {
        val now = 1_000_000_000L
        val result = ServerFailureScorer.weightedFailures(
            listOf(
                ConnectionHistoryStore.Entry(now - 1L, "error", "  NODE A ", "VLESS"),
                ConnectionHistoryStore.Entry(now - 2L, "error", "node a", "TUIC"),
                ConnectionHistoryStore.Entry(now - 3L, "error", "Node B", "VLESS")
            ),
            now
        )
        assertEquals(6, result["node a"])
        assertEquals(3, result["node b"])
    }

    @Test
    fun rejectsInvalidClockAndWindow() {
        val sample = ConnectionHistoryStore.Entry(1L, "error", "Node", "VLESS")
        assertTrue(ServerFailureScorer.weightedFailures(listOf(sample), -1L).isEmpty())
        assertTrue(ServerFailureScorer.weightedFailures(listOf(sample), 2L, 0L).isEmpty())
    }
}
