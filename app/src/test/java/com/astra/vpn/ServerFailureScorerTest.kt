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
        assertEquals(6, result["node a|vless"])
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
    fun normalizesNamesAndSeparatesProtocols() {
        val now = 1_000_000_000L
        val result = ServerFailureScorer.weightedFailures(
            listOf(
                ConnectionHistoryStore.Entry(now - 1L, "error", "  NODE A ", "VLESS"),
                ConnectionHistoryStore.Entry(now - 2L, "error", "node a", "TUIC"),
                ConnectionHistoryStore.Entry(now - 3L, "error", "Node B", "VLESS")
            ),
            now
        )
        assertEquals(3, result["node a|vless"])
        assertEquals(3, result["node a|tuic"])
        assertEquals(3, result["node b|vless"])
    }

    @Test
    fun rejectsInvalidClockAndWindow() {
        val sample = ConnectionHistoryStore.Entry(1L, "error", "Node", "VLESS")
        assertTrue(ServerFailureScorer.weightedFailures(listOf(sample), -1L).isEmpty())
        assertTrue(ServerFailureScorer.weightedFailures(listOf(sample), 2L, 0L).isEmpty())
    }

    @Test
    fun rejectsZeroTimestampsAndZeroClock() {
        val zeroTimestamp = ConnectionHistoryStore.Entry(0L, "error", "Node", "VLESS")
        assertTrue(ServerFailureScorer.weightedFailures(listOf(zeroTimestamp), 1_000L).isEmpty())
        assertTrue(ServerFailureScorer.weightedFailures(listOf(entry(0L, now = 0L)), 0L).isEmpty())

        val profile = "vless://id@node.example:443#Node"
        assertEquals(
            listOf(profile),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                listOf(profile), listOf(zeroTimestamp), 1_000L,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() }
            )
        )
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

    @Test
    fun repeatedRecentFailuresEscalateQuarantineForOnlyThatServer() {
        val now = 10_000_000L
        val bad = "vless://id@bad.example:443#Bad"
        val good = "tuic://id:pass@good.example:443#Good"
        val entries = listOf(
            ConnectionHistoryStore.Entry(now - 2L * 60L * 1_000L, "error", "Bad", "VLESS"),
            ConnectionHistoryStore.Entry(now - 60_000L, "error", "Bad", "VLESS")
        )
        assertEquals(
            listOf(good),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                listOf(bad, good), entries, now,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() }
            )
        )
    }

    @Test
    fun escalatedQuarantineExpiresFromMostRecentFailure() {
        val now = 10_000_000L
        val profile = "vless://id@bad.example:443#Bad"
        val entries = listOf(
            ConnectionHistoryStore.Entry(now - 12L * 60L * 1_000L, "error", "Bad", "VLESS"),
            ConnectionHistoryStore.Entry(now - 11L * 60L * 1_000L, "error", "Bad", "VLESS")
        )
        assertEquals(
            listOf(profile),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                listOf(profile), entries, now,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() }
            )
        )
    }

    @Test
    fun quarantineEscalationDoesNotCrossProtocolAliases() {
        val now = 10_000_000L
        val hysteria = "hysteria2://id@node.example:443#Node"
        val entries = listOf(
            ConnectionHistoryStore.Entry(now - 60_000L, "error", "Node", "VLESS"),
            ConnectionHistoryStore.Entry(now - 30_000L, "error", "Node", "VLESS")
        )
        assertEquals(
            listOf(hysteria),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                listOf(hysteria), entries, now,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() }
            )
        )
    }

    @Test
    fun invalidEscalationWindowDisablesQuarantineSafely() {
        val now = 1_000_000L
        val profile = "vless://id@bad.example:443#Bad"
        val entries = listOf(ConnectionHistoryStore.Entry(now, "error", "Bad", "VLESS"))
        assertEquals(
            listOf(profile),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                listOf(profile), entries, now,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() },
                escalationWindowMs = 0L
            )
        )
    }

    @Test
    fun repeatedFailuresUseThirtyMinuteMaximumQuarantine() {
        val now = 10_000_000L
        val profile = "vless://id@bad.example:443#Bad"
        val healthy = "tuic://id:pass@good.example:443#Good"
        val profiles = listOf(profile, healthy)
        val entries = listOf(1L, 2L, 3L, 4L).map { minutes ->
            ConnectionHistoryStore.Entry(
                now - minutes * 60L * 1_000L, "error", "Bad", "VLESS"
            )
        }
        val nameOf: (String) -> String = { it.substringAfterLast('#') }
        val protocolOf: (String) -> String = { it.substringBefore("://").uppercase() }

        assertEquals(
            listOf(healthy),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                profiles, entries, now,
                nameOf = nameOf, protocolOf = protocolOf
            )
        )
        assertEquals(
            profiles,
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                profiles, entries, now + 35L * 60L * 1_000L,
                nameOf = nameOf, protocolOf = protocolOf
            )
        )
    }


    @Test
    fun validatedSuccessClearsOlderFailurePenalty() {
        val now = 1_000_000_000L
        val result = ServerFailureScorer.weightedFailures(
            listOf(
                ConnectionHistoryStore.Entry(now - 1L, "connected", "Node A", "VLESS"),
                ConnectionHistoryStore.Entry(now - 2L, "error", "Node A", "VLESS"),
                ConnectionHistoryStore.Entry(now - 3L, "error", "Node A", "VLESS")
            ),
            now
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun failureAfterValidatedSuccessStillAffectsReliability() {
        val now = 1_000_000_000L
        val result = ServerFailureScorer.weightedFailures(
            listOf(
                ConnectionHistoryStore.Entry(now - 1L, "error", "Node A", "VLESS"),
                ConnectionHistoryStore.Entry(now - 2L, "connected", "Node A", "VLESS"),
                ConnectionHistoryStore.Entry(now - 3L, "error", "Node A", "VLESS")
            ),
            now
        )
        assertEquals(3, result["node a|vless"])
    }

    @Test
    fun validatedSuccessReleasesServerQuarantine() {
        val now = 1_000_000L
        val profile = "vless://id@bad.example:443#Bad"
        val entries = listOf(
            ConnectionHistoryStore.Entry(now - 1L, "connected", "Bad", "VLESS"),
            ConnectionHistoryStore.Entry(now - 2L, "error", "Bad", "VLESS")
        )
        assertEquals(
            listOf(profile),
            ServerFailureScorer.excludeRecentlyFailedProfiles(
                listOf(profile), entries, now,
                nameOf = { it.substringAfterLast('#') },
                protocolOf = { it.substringBefore("://").uppercase() }
            )
        )
    }

}
