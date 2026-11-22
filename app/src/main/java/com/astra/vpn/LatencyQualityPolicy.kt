package com.astra.vpn

/** One latency scale shared by server rows, quick-switch tiles and orbit markers. */
internal object LatencyQualityPolicy {
    fun band(latencyMs: Long?): Int = when {
        latencyMs == null || latencyMs <= 0L -> 0
        latencyMs <= 100L -> 3
        latencyMs <= 250L -> 2
        else -> 1
    }

    fun label(latencyMs: Long): String = when (band(latencyMs)) {
        3 -> "отлично"
        2 -> "средне"
        1 -> "высоко"
        else -> "н/д"
    }
}
