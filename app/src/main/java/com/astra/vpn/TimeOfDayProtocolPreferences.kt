package com.astra.vpn

import android.content.SharedPreferences
import java.util.Locale

/**
 * Persists per-time-window protocol preferences without storing profile URIs
 * or credentials. Unknown values are ignored when loading older/corrupt data.
 */
internal object TimeOfDayProtocolPreferences {
    private const val KEY = "time_of_day_protocol_preferences"
    private const val ENTRY_SEPARATOR = ";"
    private const val VALUE_SEPARATOR = ":"

    private val allowedProtocols = setOf("vless", "hysteria2", "tuic")

    fun load(preferences: SharedPreferences): Map<TimeOfDayServerPolicy.Window, Set<String>> {
        val encoded = preferences.getString(KEY, null) ?: return emptyMap()
        return decode(encoded)
    }

    fun save(
        preferences: SharedPreferences,
        values: Map<TimeOfDayServerPolicy.Window, Set<String>>
    ) {
        preferences.edit().putString(KEY, encode(values)).apply()
    }

    internal fun encode(
        values: Map<TimeOfDayServerPolicy.Window, Set<String>>
    ): String = TimeOfDayServerPolicy.Window.values().mapNotNull { window ->
        val protocols = values[window].orEmpty()
            .mapNotNull(::normalizeProtocol)
            .distinct()
            .sorted()
        if (protocols.isEmpty()) null
        else window.name + VALUE_SEPARATOR + protocols.joinToString(",")
    }.joinToString(ENTRY_SEPARATOR)

    internal fun decode(encoded: String): Map<TimeOfDayServerPolicy.Window, Set<String>> {
        if (encoded.isBlank()) return emptyMap()
        val result = linkedMapOf<TimeOfDayServerPolicy.Window, Set<String>>()
        encoded.split(ENTRY_SEPARATOR).forEach { entry ->
            val parts = entry.split(VALUE_SEPARATOR, limit = 2)
            if (parts.size != 2) return@forEach
            val window = runCatching { TimeOfDayServerPolicy.Window.valueOf(parts[0]) }.getOrNull()
                ?: return@forEach
            val protocols = parts[1].split(",").mapNotNull(::normalizeProtocol).toSet()
            if (protocols.isNotEmpty()) result[window] = protocols
        }
        return result
    }

    internal fun normalizeProtocol(value: String): String? {
        val normalized = value.trim().lowercase(Locale.ROOT)
        val canonical = if (normalized == "hy2") "hysteria2" else normalized
        return canonical.takeIf { it in allowedProtocols }
    }
}
