package com.astra.vpn

/** Simple linear estimate based on bytes consumed so far in the current day. */
internal object TrafficForecast {
    fun projectedDailyBytes(consumedBytes: Long, elapsedDayMs: Long, dayLengthMs: Long = 86_400_000L): Long? {
        if (consumedBytes < 0L || elapsedDayMs <= 0L || dayLengthMs <= 0L) return null
        val safeElapsed = elapsedDayMs.coerceAtMost(dayLengthMs)
        return (consumedBytes.toDouble() * dayLengthMs / safeElapsed)
            .takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }
            ?.toLong()
    }
}
