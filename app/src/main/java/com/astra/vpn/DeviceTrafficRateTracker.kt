package com.astra.vpn

/**
 * Calculates device-wide throughput from cumulative TrafficStats counters.
 * Uses monotonic elapsed time and discards samples across pauses, counter resets,
 * or clock-epoch changes so they cannot create misleading speed spikes.
 */
internal class DeviceTrafficRateTracker(
    private val maxSampleGapMs: Long = 5_000L
) {
    data class Rate(val downloadMbps: Double, val uploadMbps: Double)

    private var previousRxBytes: Long? = null
    private var previousTxBytes: Long? = null
    private var previousAtElapsedMs: Long? = null

    init { require(maxSampleGapMs > 0L) }

    @Synchronized
    fun update(rxBytes: Long, txBytes: Long, nowElapsedMs: Long): Rate? {
        if (rxBytes < 0L || txBytes < 0L || nowElapsedMs <= 0L) {
            reset()
            return null
        }
        val oldRx = previousRxBytes
        val oldTx = previousTxBytes
        val oldAt = previousAtElapsedMs
        if (oldRx == null || oldTx == null || oldAt == null) {
            setBaseline(rxBytes, txBytes, nowElapsedMs)
            return null
        }
        val elapsedMs = nowElapsedMs - oldAt
        if (nowElapsedMs <= oldAt || elapsedMs > maxSampleGapMs ||
            rxBytes < oldRx || txBytes < oldTx
        ) {
            setBaseline(rxBytes, txBytes, nowElapsedMs)
            return null
        }
        val seconds = elapsedMs / 1_000.0
        val result = Rate(
            downloadMbps = (rxBytes - oldRx).toDouble() * 8.0 / seconds / 1_000_000.0,
            uploadMbps = (txBytes - oldTx).toDouble() * 8.0 / seconds / 1_000_000.0
        )
        setBaseline(rxBytes, txBytes, nowElapsedMs)
        return result
    }

    @Synchronized
    fun reset() {
        previousRxBytes = null
        previousTxBytes = null
        previousAtElapsedMs = null
    }

    private fun setBaseline(rxBytes: Long, txBytes: Long, nowElapsedMs: Long) {
        previousRxBytes = rxBytes
        previousTxBytes = txBytes
        previousAtElapsedMs = nowElapsedMs
    }
}
