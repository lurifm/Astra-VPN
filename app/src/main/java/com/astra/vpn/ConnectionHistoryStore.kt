package com.astra.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps a small, local-only history of VPN state changes.
 * Server URIs, subscription links and credentials are deliberately never stored.
 */
class ConnectionHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("astra_connection_history", Context.MODE_PRIVATE)

    data class Entry(
        val timestamp: Long,
        val state: String,
        val serverName: String,
        val protocol: String,
        val serverId: String = ""
    )

    fun record(state: String, serverName: String, protocol: String, profile: String = "") =
        synchronized(STORE_LOCK) {
        val entries = readArray()
        val updated = JSONArray()
        updated.put(
            JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("state", state.take(24))
                .put("server", serverName.take(100))
                .put("protocol", protocol.take(32))
                .put("serverId", ProfileIdentity.of(profile))
        )
        for (index in 0 until minOf(entries.length(), MAX_ENTRIES - 1)) {
            val item = entries.optJSONObject(index) ?: continue
            updated.put(item)
        }
        prefs.edit().putString(KEY_ENTRIES, updated.toString()).apply()
    }

    fun getEntries(): List<Entry> = synchronized(STORE_LOCK) {
        val array = readArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    Entry(
                        timestamp = item.optLong("timestamp"),
                        state = item.optString("state", "unknown"),
                        serverName = item.optString("server", "Сервер не выбран"),
                        protocol = item.optString("protocol", "—"),
                        serverId = item.optString("serverId", "")
                    )
                )
            }
        }
    }

    fun clear() = synchronized(STORE_LOCK) {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun readArray(): JSONArray {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private companion object {
        val STORE_LOCK = Any()
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 100
    }
}
