package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStabilityTest {
    @Test fun totalsConnectedTimeAndCountsUnexpectedDrops() {
        val events = listOf(
            ConnectionStability.Event(300_000L, "error"),
            ConnectionStability.Event(200_000L, "connected"),
            ConnectionStability.Event(150_000L, "disconnected"),
            ConnectionStability.Event(100_000L, "connected")
        )
        val result = ConnectionStability.summarize(events, 400_000L)
        assertEquals(150_000L, result.connectedDurationMs)
        assertEquals(2, result.completedSessions)
        assertEquals(1, result.unexpectedDrops)
    }

    @Test fun includesAnActiveSessionUpToNow() {
        val result = ConnectionStability.summarize(
            listOf(ConnectionStability.Event(10_000L, "connected")),
            70_000L
        )
        assertEquals(60_000L, result.connectedDurationMs)
        assertEquals(0, result.completedSessions)
        assertEquals(0, result.unexpectedDrops)
    }

    @Test fun ignoresDisconnectsWithoutARecordedSession() {
        val result = ConnectionStability.summarize(
            listOf(ConnectionStability.Event(30_000L, "disconnected")),
            50_000L
        )
        assertEquals(0L, result.connectedDurationMs)
        assertEquals(0, result.completedSessions)
        assertEquals(0, result.unexpectedDrops)
    }

    @Test fun formatsDurations() {
        assertEquals("2 ч 5 мин", ConnectionStability.formatDuration(7_500_000L))
        assertEquals("1 д 2 ч", ConnectionStability.formatDuration(93_600_000L))
    }
}
