package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHistoryStoreTest {
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

    @Test(expected = org.json.JSONException::class)
    fun decodeRejectsMalformedJsonInsteadOfTreatingItAsEmpty() {
        ConnectionHistoryStore.decodeEntries("{not-json")
    }
}
