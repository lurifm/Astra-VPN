package com.astra.vpn

import java.util.Locale

/**
 * Keeps reachable servers first when sorting by measured latency.
 * Missing/failed measurements (null) are always placed at the bottom.
 */
object ServerLatencySort {
    fun sort(
        profiles: List<String>,
        mode: String,
        latencyMs: Map<String, Long>,
        favorites: Set<String>,
        locale: Locale = Locale.getDefault()
    ): List<String> = when (mode) {
        "ping" -> profiles.sortedWith(
            compareBy<String> { latencyMs[it]?.takeIf { value -> value > 0L } ?: Long.MAX_VALUE }
                .thenByDescending { it in favorites }
        )
        "alphabet" -> profiles.sortedBy { it.lowercase(locale) }
        else -> profiles.sortedByDescending { it in favorites }
    }
}
