package com.astra.vpn

/** Small, deterministic summaries for app-traffic history. */
internal object AppTrafficTrend {
    private const val DEFAULT_MIN_DELTA = 50L * 1024L * 1024L

    fun describe(currentBytes: Long, previousBytes: Long, minDeltaBytes: Long = DEFAULT_MIN_DELTA): String? {
        val current = currentBytes.coerceAtLeast(0L)
        val previous = previousBytes.coerceAtLeast(0L)
        val delta = current - previous
        if (previous <= 0L || kotlin.math.abs(delta.toDouble()) < minDeltaBytes.coerceAtLeast(0L).toDouble()) {
            return null
        }
        val percent = kotlin.math.abs(delta.toDouble()) * 100.0 / previous.toDouble()
        val rounded = String.format(java.util.Locale.getDefault(), "%.0f", percent)
        return if (delta > 0L) "↑ +$rounded% ко вчера" else "↓ −$rounded% ко вчера"
    }

    fun dailyTotals(days: List<AppTrafficStatsReader.DailySnapshot>): List<Long> =
        days.map { day -> day.apps.sumOf { it.totalBytes.coerceAtLeast(0L) }.coerceAtLeast(0L) }
}
