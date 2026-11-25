package com.astra.vpn

/** Forecasts typical daily device-wide traffic from completed days only. */
internal object TrafficForecast {
    data class Result(val averageDailyBytes: Long, val sampleDays: Int)

    fun estimate(entries: List<DailyTrafficHistory.Entry>, todayKey: Int): Result? {
        // Only strictly earlier calendar days are complete. Excluding just today
        // would let a future-dated/corrupt entry inflate the forecast.
        val completed = entries.filter { it.dayKey < todayKey }
            .sortedByDescending { it.dayKey }
            .take(6)
        if (completed.size < 2) return null
        val totals = completed.map {
            TrafficCounterMath.add(it.totals.downloadedBytes, it.totals.uploadedBytes)
        }
        val average = totals.sumOf { it / totals.size } +
            (totals.sumOf { it % totals.size } / totals.size)
        return Result(average, completed.size)
    }
}
