package com.astra.vpn

/** Detects a sustained drop while traffic is still flowing; idle periods are ignored. */
internal class SpeedDropFailoverPolicy(
    private val thresholdFraction: Double = 0.5,
    private val minimumBaselineMbps: Double = 1.0,
    private val minimumActiveMbps: Double = 0.15,
    private val sustainedMs: Long = 10_000L,
    private val cooldownMs: Long = 120_000L
) {
    private data class Sample(val at: Long, val mbps: Double)
    private val samples = ArrayDeque<Sample>()
    private var belowSince: Long? = null
    private var lastTriggeredAt = Long.MIN_VALUE

    fun update(downloadMbps: Double, now: Long, vpnConnected: Boolean): Boolean {
        if (!vpnConnected || now <= 0L || !downloadMbps.isFinite() || downloadMbps < minimumActiveMbps) {
            belowSince = null
            return false
        }
        samples.addLast(Sample(now, downloadMbps))
        while (samples.isNotEmpty() && now - samples.first().at > 30_000L) samples.removeFirst()
        val baseline = samples.maxOfOrNull { it.mbps } ?: return false
        if (baseline < minimumBaselineMbps || downloadMbps >= baseline * thresholdFraction) {
            belowSince = null
            return false
        }
        if (belowSince == null) belowSince = now
        if (now - (belowSince ?: now) < sustainedMs) return false
        if (lastTriggeredAt != Long.MIN_VALUE && now - lastTriggeredAt < cooldownMs) return false
        lastTriggeredAt = now
        belowSince = null
        samples.clear()
        return true
    }
}
