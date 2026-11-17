package com.astra.vpn

/**
 * Pure calculations for subscription usage. Provider values are untrusted,
 * so negative counters are ignored and additions saturate instead of wrapping.
 */
object SubscriptionUsageCalculator {
    fun usedBytes(uploadBytes: Long?, downloadBytes: Long?): Long {
        val upload = (uploadBytes ?: 0L).coerceAtLeast(0L)
        val download = (downloadBytes ?: 0L).coerceAtLeast(0L)
        return if (Long.MAX_VALUE - upload < download) Long.MAX_VALUE else upload + download
    }

    /** Remaining quota without risking underflow; negative limits are unavailable. */
    fun remainingBytes(usedBytes: Long, totalBytes: Long): Long? {
        if (totalBytes < 0L) return null
        val safeUsed = usedBytes.coerceAtLeast(0L)
        return if (safeUsed >= totalBytes) 0L else totalBytes - safeUsed
    }

    /** Returns a clamped 0..100 percentage, or null when no valid limit exists. */
    fun percentage(usedBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        val safeUsed = usedBytes.coerceAtLeast(0L)
        return ((safeUsed.toDouble() / totalBytes.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }
}
