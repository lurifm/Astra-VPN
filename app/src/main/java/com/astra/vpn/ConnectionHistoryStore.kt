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
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            if (!isValidEntry(item)) continue
            if (updated.length() >= MAX_ENTRIES) break
            updated.put(item)
        }
        prefs.edit().putString(KEY_ENTRIES, updated.toString()).apply()
    }

    fun getEntries(): List<Entry> = synchronized(STORE_LOCK) {
        runCatching { decodeEntries(prefs.getString(KEY_ENTRIES, null)) }
            .getOrElse { emptyList() }
    }

    fun clear() = synchronized(STORE_LOCK) {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun readArray(): JSONArray {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return JSONArray()
        // Do not silently replace an unreadable history with an empty array on
        // the next append. Let the caller report the failure and keep the raw
        // preference untouched until the user explicitly clears the history.
        return try {
            JSONArray(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Connection history is unreadable", error)
        }
    }

    companion object {
        val STORE_LOCK = Any()
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 100
        private val VALID_STATES = setOf("connecting", "connected", "disconnected", "error")

        internal fun decodeEntries(raw: String?): List<Entry> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = JSONArray(raw)
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    if (!isValidEntry(item)) continue
                    if (size >= MAX_ENTRIES) break
                    add(
                        Entry(
                            timestamp = item.optLong("timestamp"),
                            state = item.optString("state"),
                            serverName = item.optString("server", "Сервер не выбран").take(100),
                            protocol = item.optString("protocol", "—").take(32),
                            serverId = item.optString("serverId", "").takeIf {
                                it.isEmpty() || it.matches(Regex("[0-9a-fA-F]{64}"))
                            }.orEmpty()
                        )
                    )
                }
            }
        }

        private fun isValidEntry(item: JSONObject): Boolean {
            val timestamp = item.optLong("timestamp", -1L)
            val state = item.optString("state", "")
            val serverId = item.optString("serverId", "")
            return timestamp > 0L &&
                state in VALID_STATES &&
                (serverId.isEmpty() || serverId.matches(Regex("[0-9a-fA-F]{64}")))
        }
    }
}
