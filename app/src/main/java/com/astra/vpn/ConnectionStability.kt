package com.astra.vpn

/** Derives conservative stability metrics from recorded VPN state transitions. */
object ConnectionStability {
    data class Summary(
        val connectedDurationMs: Long,
        val completedSessions: Int,
        val unexpectedDrops: Int
    )

    data class Event(val timestamp: Long, val state: String)

    fun summarize(eventsNewestFirst: List<Event>, now: Long): Summary {
        var connectedAt: Long? = null
        var duration = 0L
        var sessions = 0
        var drops = 0
        eventsNewestFirst.asReversed().forEach { event ->
            when (event.state) {
                "connected" -> if (connectedAt == null) connectedAt = event.timestamp
                "disconnected", "error" -> {
                    val start = connectedAt
                    if (start != null) {
                        duration += (event.timestamp - start).coerceAtLeast(0L)
                        sessions++
                        if (event.state == "error") drops++
                        connectedAt = null
                    }
                }
            }
        }
        connectedAt?.let { start ->
            duration += (now - start).coerceAtLeast(0L)
        }
        return Summary(duration, sessions, drops)
    }

    fun formatDuration(durationMs: Long): String {
        val minutes = durationMs.coerceAtLeast(0L) / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            days > 0 -> "${days} д ${hours % 24} ч"
            hours > 0 -> "${hours} ч ${minutes % 60} мин"
            else -> "${minutes} мин"
        }
    }
}
