package com.astra.vpn

import java.util.Calendar
import java.util.Locale

/** Counts consecutive local calendar days with at least one successful VPN connection. */
internal object VpnUsageStreak {
    fun currentDays(
        connectedTimestamps: List<Long>,
        now: Long,
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): Int {
        if (now <= 0L) return 0
        val calendar = Calendar.getInstance(timeZone, Locale.getDefault()).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val connectedDays = connectedTimestamps.asSequence()
            .filter { it > 0L && it <= now }
            .map { timestamp ->
                Calendar.getInstance(timeZone, Locale.getDefault()).apply {
                    timeInMillis = timestamp
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }.toSet()
        if (connectedDays.isEmpty()) return 0

        // If there has been no connection today, a streak may still be active
        // when yesterday was the last connected day.
        if (calendar.timeInMillis !in connectedDays) calendar.add(Calendar.DAY_OF_YEAR, -1)
        var streak = 0
        while (calendar.timeInMillis in connectedDays) {
            streak++
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }
}
