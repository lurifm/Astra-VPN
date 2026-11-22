package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHistoryStoreTest {

    @Test
    fun createEntryJsonValidatesAndBoundsNewHistoryRecords() {
        val privateProfile = "vless://secret-user:secret-pass@example.com:443?type=tcp"
        val json = ConnectionHistoryStore.createEntryJson(
            state = "connected",
            serverName = "S".repeat(120),
            protocol = "P".repeat(40),
            profile = privateProfile,
            timestamp = 1700000000000L
        )

        assertEquals(1700000000000L, json.getLong("timestamp"))
        assertEquals("connected", json.getString("state"))
        assertEquals(100, json.getString("server").length)
        assertEquals(32, json.getString("protocol").length)
        assertEquals(ProfileIdentity.of(privateProfile), json.getString("serverId"))
        assertFalse(json.toString().contains(privateProfile))
        assertEquals(64, json.getString("serverId").length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun createEntryJsonRejectsUnsupportedStates() {
        ConnectionHistoryStore.createEntryJson(
            state = "tunnel_ready",
            serverName = "Server",
            protocol = "VLESS",
            profile = "",
            timestamp = 1700000000000L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun createEntryJsonRejectsNonPositiveTimestamps() {
        ConnectionHistoryStore.createEntryJson(
            state = "connected",
            serverName = "Server",
            protocol = "VLESS",
            profile = "",
            timestamp = 0L
        )
    }

    @Test
    fun decodeKeepsValidEntriesAndSkipsCorruptedRows() {
        val raw = """
            [
              {"timestamp":1700000000000,"state":"connected","server":"Good","protocol":"VLESS","serverId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
              {"timestamp":0,"state":"connected","server":"Bad time","protocol":"VLESS"},
              {"timestamp":1700000000001,"state":"unknown-state","server":"Bad state","protocol":"VLESS"},
              null,
              {"timestamp":1700000000002,"state":"error","server":"Legacy","protocol":"VMess"}
            ]
        """.trimIndent()

        val entries = ConnectionHistoryStore.decodeEntries(raw)

        assertEquals(2, entries.size)
        assertEquals("Good", entries[0].serverName)
        assertEquals("Legacy", entries[1].serverName)
        assertEquals("", entries[1].serverId)
    }

    @Test
    fun decodeReturnsEmptyForMissingHistory() {
        assertTrue(ConnectionHistoryStore.decodeEntries(null).isEmpty())
        assertTrue(ConnectionHistoryStore.decodeEntries("").isEmpty())
    }

    @Test
    fun decodeCapsRestoredHistoryAtOneHundredNewestEntries() {
        val rows = (1..105).joinToString(",") { index ->
            """{"timestamp":${1700000000000L + index},"state":"connected","server":"Server $index","protocol":"VLESS"}"""
        }
        val entries = ConnectionHistoryStore.decodeEntries("[$rows]")

        assertEquals(100, entries.size)
        assertEquals("Server 1", entries.first().serverName)
        assertEquals("Server 100", entries.last().serverName)
    }

    @Test(expected = org.json.JSONException::class)
    fun decodeRejectsMalformedJsonInsteadOfTreatingItAsEmpty() {
        ConnectionHistoryStore.decodeEntries("{not-json")
    }
}
