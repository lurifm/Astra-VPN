package com.astra.vpn

import java.util.Calendar
import java.util.TimeZone

/** Optional time-window preference; neutral by default and never overrides reachability. */
internal object TimeOfDayServerPolicy {
    enum class Window { MORNING, DAY, EVENING, NIGHT }

    fun window(hour: Int): Window = when (hour.coerceIn(0, 23)) {
        in 6..10 -> Window.MORNING
        in 11..16 -> Window.DAY
        in 17..22 -> Window.EVENING
        else -> Window.NIGHT
    }

    fun preferredScoreBonus(
        profile: String,
        hour: Int,
        preferredProtocols: Map<Window, Set<String>>
    ): Int {
        val protocol = profile.substringBefore("://").lowercase(java.util.Locale.ROOT)
            .let { if (it == "hy2") "hysteria2" else it }
        val configured = preferredProtocols[window(hour)].orEmpty()
            .map { it.lowercase(java.util.Locale.ROOT).let { value -> if (value == "hy2") "hysteria2" else value } }
        return if (protocol in configured) 8 else 0
    }

    fun localHour(now: Long, timeZone: TimeZone = TimeZone.getDefault()): Int =
        Calendar.getInstance(timeZone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
}
