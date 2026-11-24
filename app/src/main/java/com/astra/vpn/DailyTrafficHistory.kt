package com.astra.vpn

/**
 * Rolling history of daily device-wide traffic totals.
 *
 * Storage is deliberately plain and bounded: no app identifiers, destinations,
 * server URLs, or other network metadata are retained.
 */
internal class DailyTrafficHistory private constructor(
    private val days: LinkedHashMap<Int, Totals>
) {
    data class Totals(val downloadedBytes: Long, val uploadedBytes: Long)
    data class Entry(val dayKey: Int, val totals: Totals)

    constructor() : this(linkedMapOf())

    fun record(dayKey: Int, downloadedBytes: Long, uploadedBytes: Long) {
        if (!isValidDayKey(dayKey)) return
        days[dayKey] = Totals(downloadedBytes.coerceAtLeast(0L), uploadedBytes.coerceAtLeast(0L))
        val keep = days.keys.sortedDescending().take(MAX_DAYS).toSet()
        days.keys.filter { it !in keep }.toList().forEach(days::remove)
    }

    fun entries(): List<Entry> = days.entries.sortedByDescending { it.key }
        .map { Entry(it.key, it.value) }

    fun total(): Totals = Totals(
        days.values.fold(0L) { total, day -> TrafficCounterMath.add(total, day.downloadedBytes) },
        days.values.fold(0L) { total, day -> TrafficCounterMath.add(total, day.uploadedBytes) }
    )

    fun encode(): String = entries().joinToString(";") {
        "${it.dayKey},${it.totals.downloadedBytes},${it.totals.uploadedBytes}"
    }

    companion object {
        const val MAX_DAYS = 7

        private fun isValidDayKey(dayKey: Int): Boolean {
            if (dayKey !in 19000101..29991231) return false
            val parser = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                isLenient = false
            }
            return runCatching {
                val parsed = parser.parse(dayKey.toString()) ?: return false
                parser.format(parsed) == dayKey.toString()
            }.getOrDefault(false)
        }

        fun decode(raw: String?): DailyTrafficHistory {
            val parsed = linkedMapOf<Int, Totals>()
            raw.orEmpty().split(";").forEach { row ->
                val fields = row.split(",")
                if (fields.size == 3) {
                    val day = fields[0].toIntOrNull()
                    val rx = fields[1].toLongOrNull()
                    val tx = fields[2].toLongOrNull()
                    if (day != null && isValidDayKey(day) && rx != null && tx != null && rx >= 0L && tx >= 0L) {
                        parsed[day] = Totals(rx, tx)
                    }
                }
            }
            val history = DailyTrafficHistory(parsed)
            val keep = parsed.keys.sortedDescending().take(MAX_DAYS).toSet()
            parsed.keys.filter { it !in keep }.toList().forEach(parsed::remove)
            return history
        }
    }
}
