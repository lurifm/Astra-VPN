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
        updated.put(createEntryJson(state, serverName, protocol, profile, System.currentTimeMillis()))
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
        private val STORE_LOCK = Any()
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 100
        private val VALID_STATES = setOf("connecting", "connected", "disconnected", "error")

        internal fun createEntryJson(
            state: String,
            serverName: String,
            protocol: String,
            profile: String,
            timestamp: Long
        ): JSONObject {
            require(state in VALID_STATES) { "Unsupported connection history state" }
            require(timestamp > 0L) { "Connection history timestamp must be positive" }
            return JSONObject()
                .put("timestamp", timestamp)
                .put("state", state)
                .put("server", serverName.take(100))
                .put("protocol", protocol.take(32))
                .put("serverId", ProfileIdentity.of(profile))
        }

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
            // JSONObject.optLong/optString coerce malformed JSON values (for
            // example a numeric state or a string timestamp). History is
            // best-effort data: reject malformed field types instead of
            // silently turning them into plausible records.
            val timestampValue = item.opt("timestamp") as? Number ?: return false
            val timestamp = timestampValue.toLong()
            if (timestamp <= 0L || timestampValue.toDouble() != timestamp.toDouble()) return false

            val state = item.opt("state") as? String ?: return false
            if (state !in VALID_STATES) return false

            val serverId = if (item.has("serverId")) {
                item.opt("serverId") as? String ?: return false
            } else {
                "" // Legacy entries predate profile fingerprints.
            }
            if (serverId.isNotEmpty() && !serverId.matches(Regex("[0-9a-fA-F]{64}"))) return false

            val server = item.opt("server")
            if (server != null && server !is String) return false
            val protocol = item.opt("protocol")
            if (protocol != null && protocol !is String) return false
            return true
        }
    }
}
