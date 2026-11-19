package com.astra.vpn

/**
 * Debounces severe throughput degradation before requesting a server failover.
 * The caller must provide VPN-specific measurements, never device-wide counters.
 */
internal class ThroughputDropFailoverPolicy(
    private val dropRatio: Double = DEFAULT_DROP_RATIO,
    private val sustainedMs: Long = DEFAULT_SUSTAINED_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private var degradationStartedAt: Long? = null
    private var lastFailoverAt: Long? = null

    init {
        require(dropRatio in 0.0..1.0)
        require(sustainedMs > 0L)
        require(cooldownMs >= 0L)
    }

    @Synchronized
    fun shouldFailover(baselineMbps: Double, currentMbps: Double, nowMs: Long): Boolean {
        if (!baselineMbps.isFinite() || baselineMbps <= 0.0 ||
            !currentMbps.isFinite() || currentMbps <= 0.0 || nowMs <= 0L
        ) {
            degradationStartedAt = null
            return false
        }
        if (currentMbps > baselineMbps * dropRatio) {
            degradationStartedAt = null
            return false
        }
        val last = lastFailoverAt
        if (last != null && (nowMs < last || nowMs - last < cooldownMs)) {
            degradationStartedAt = null
            return false
        }
        val started = degradationStartedAt
        if (started == null || nowMs < started) {
            degradationStartedAt = nowMs
            return false
        }
        if (nowMs - started < sustainedMs) return false
        lastFailoverAt = nowMs
        degradationStartedAt = null
        return true
    }

    @Synchronized
    fun reset() {
        degradationStartedAt = null
        lastFailoverAt = null
    }

    companion object {
        const val DEFAULT_DROP_RATIO = 0.5
        const val DEFAULT_SUSTAINED_MS = 10_000L
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}
