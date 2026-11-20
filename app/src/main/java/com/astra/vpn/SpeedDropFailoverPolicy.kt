package com.astra.vpn

/** Detects a sustained drop while traffic is still flowing; idle periods are ignored. */
internal class SpeedDropFailoverPolicy(
    private val thresholdFraction: Double = 0.5,
    private val minimumBaselineMbps: Double = 1.0,
    private val minimumActiveMbps: Double = 0.15,
    private val sustainedMs: Long = 10_000L,
    private val cooldownMs: Long = 120_000L,
    private val maxSampleGapMs: Long = 3_000L
) {
    private data class Sample(val at: Long, val mbps: Double)
    private val samples = ArrayDeque<Sample>()
    private var belowSince: Long? = null
    private var lastSampleAt: Long? = null
    private var lastTriggeredAt = Long.MIN_VALUE

    init {
        require(thresholdFraction in 0.0..1.0)
        require(minimumBaselineMbps >= 0.0 && minimumBaselineMbps.isFinite())
        require(minimumActiveMbps >= 0.0 && minimumActiveMbps.isFinite())
        require(sustainedMs > 0L && cooldownMs >= 0L && maxSampleGapMs > 0L)
    }

    fun update(
        downloadMbps: Double,
        now: Long,
        vpnConnected: Boolean,
        trafficIsVpnScoped: Boolean = false
    ): Boolean {
        // Aggregate device counters include unrelated apps and must never trigger failover.
        if (!trafficIsVpnScoped || !vpnConnected || now <= 0L ||
            !downloadMbps.isFinite() || downloadMbps < minimumActiveMbps
        ) {
            resetObservationWindow()
            return false
        }

        val previousAt = lastSampleAt
        if (previousAt != null) {
            // Wall-clock rollback or duplicate timestamps invalidate the current observation.
            if (now <= previousAt) {
                resetObservationWindow()
            } else if (now - previousAt > maxSampleGapMs) {
                // A long sampling pause cannot prove that the drop was continuous.
                belowSince = null
            }
        }

        lastSampleAt = now
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
        lastSampleAt = null
        return true
    }

    private fun resetObservationWindow() {
        samples.clear()
        belowSince = null
        lastSampleAt = null
    }
}
