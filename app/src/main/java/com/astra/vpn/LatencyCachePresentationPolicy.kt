package com.astra.vpn

/**
 * Labels values restored from disk. Their original monotonic measurement time
 * is unavailable, so they are display-only and cannot be used for auto-selection.
 */
internal object LatencyCachePresentationPolicy {
    fun savedLabel(latencyMs: Long): String =
        "Сохранённый Ping · $latencyMs мс"

    fun isSavedLabel(label: String?): Boolean =
        label?.startsWith("Сохранённый Ping · ") == true
}
