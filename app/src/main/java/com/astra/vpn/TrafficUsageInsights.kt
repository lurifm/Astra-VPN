package com.astra.vpn

/** Small, deterministic summaries for comparing adjacent 24-hour traffic windows. */
internal object TrafficUsageInsights {
    data class Totals(val downloadedBytes: Long, val uploadedBytes: Long) {
        val combinedBytes: Long
            get() = (downloadedBytes.coerceAtLeast(0L) + uploadedBytes.coerceAtLeast(0L))
                .coerceAtLeast(0L)
    }

    fun comparison(current: Totals, previous: Totals): String {
        val now = current.combinedBytes
        val before = previous.combinedBytes
        if (before <= 0L) return if (now <= 0L) "Расход не изменился" else "Нет данных для сравнения со вчера"
        val delta = now - before
        if (delta == 0L) return "Расход не изменился относительно предыдущих 24 часов"
        val percent = (delta.toDouble() * 100.0 / before.toDouble())
        val direction = if (delta > 0L) "больше" else "меньше"
        return "На ${String.format(java.util.Locale.getDefault(), "%.0f", kotlin.math.abs(percent))}% $direction, чем за предыдущие 24 часа"
    }

    fun warning(current: Totals, previous: Totals): String? {
        val now = current.combinedBytes
        val before = previous.combinedBytes
        val oneGiB = 1024L * 1024L * 1024L
        val hundredMiB = 100L * 1024L * 1024L
        return when {
            now >= oneGiB -> "Высокий расход: за сутки использовано не менее 1 ГБ."
            before > 0L && now - before >= hundredMiB && now >= before * 2L ->
                "Расход заметно вырос: более чем вдвое и минимум на 100 МБ."
            else -> null
        }
    }
}
