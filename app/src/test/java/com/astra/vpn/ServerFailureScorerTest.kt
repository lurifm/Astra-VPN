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

    @Test
    fun excludesRecentlyFailedProfileButKeepsOtherServers() {
        val now = 1_000_000L
        val profiles = listOf("vless://id@bad.example:443#Bad", "tuic://id:pass@good.example:443#Good")
        val entries = listOf(ConnectionHistoryStore.Entry(now - 60_000L, "error", "Bad", "VLESS"))
        assertEquals(
            listOf(profiles[1]),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                profiles, entries, now,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() }
            )
        )
    }

    @Test
    fun quarantineExpiresAndDoesNotCrossProtocolIdentity() {
        val now = 1_000_000L
        val sameNameDifferentProtocol = "tuic://id:pass@node.example:443#Node"
        val entries = listOf(ConnectionHistoryStore.Entry(now - 6L * 60L * 1_000L, "error", "Node", "VLESS"))
        assertEquals(
            listOf(sameNameDifferentProtocol),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                listOf(sameNameDifferentProtocol), entries, now,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() }
            )
        )
    }

    @Test
    fun keepsLastResortCandidatesWhenAllAreQuarantined() {
        val now = 1_000_000L
        val profiles = listOf("vless://id@one.example:443#One", "tuic://id:pass@two.example:443#Two")
        val entries = listOf(
            ConnectionHistoryStore.Entry(now - 1L, "error", "One", "VLESS"),
            ConnectionHistoryStore.Entry(now - 2L, "error", "Two", "TUIC")
        )
        assertEquals(
            profiles,
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                profiles, entries, now,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() }
            )
        )
    }

}
