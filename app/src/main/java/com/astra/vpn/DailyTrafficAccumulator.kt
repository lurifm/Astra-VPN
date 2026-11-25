package com.astra.vpn

/** Daily device-wide byte totals observed while Astra's monitor is running. */
internal class DailyTrafficAccumulator(
    private var dayKey: Int = 0,
    private var lastRx: Long = -1L,
    private var lastTx: Long = -1L,
    private var downloaded: Long = 0L,
    private var uploaded: Long = 0L
) {
    data class Snapshot(val downloadedBytes: Long, val uploadedBytes: Long)

    fun update(todayKey: Int, rxBytes: Long, txBytes: Long): Snapshot {
        // A manual/system clock rollback must not move the active accounting day
        // backwards or erase totals already collected for that day.
        if (dayKey > 0 && todayKey < dayKey) return snapshot()

        if (todayKey > dayKey) {
            downloaded = 0L
            uploaded = 0L
            dayKey = todayKey
            lastRx = rxBytes
            lastTx = txBytes
            return snapshot()
        }

        if (lastRx < 0L || lastTx < 0L || rxBytes < lastRx || txBytes < lastTx) {
            lastRx = rxBytes
            lastTx = txBytes
            return snapshot()
        }
        downloaded = TrafficCounterMath.add(downloaded, (rxBytes - lastRx).coerceAtLeast(0L))
        uploaded = TrafficCounterMath.add(uploaded, (txBytes - lastTx).coerceAtLeast(0L))
        lastRx = rxBytes
        lastTx = txBytes
        return snapshot()
    }

    fun snapshot() = Snapshot(downloaded, uploaded)
    fun currentDayKey() = dayKey
    fun lastRxBytes() = lastRx
    fun lastTxBytes() = lastTx

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes Б"
            val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
            var value = bytes.toDouble()
            var unit = -1
            do {
                value /= 1024.0
                unit++
            } while (value >= 1024.0 && unit < units.lastIndex)
            return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
        }
    }
}
