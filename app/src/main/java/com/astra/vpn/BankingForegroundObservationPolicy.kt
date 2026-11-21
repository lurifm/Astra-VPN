package com.astra.vpn

/**
 * Debounces UsageEvents-derived foreground observations.
 *
 * A package is emitted only after two consecutive identical, non-null samples.
 * Ambiguous/null samples reset the candidate but never emit a synthetic
 * "bank closed" event. The caller owns the published state.
 */
internal class BankingForegroundObservationPolicy {
    private var candidate: String? = null
    private var consecutiveSamples = 0
    private var lastEmitted: String? = null

    fun observe(packageName: String?): String? {
        if (packageName.isNullOrBlank()) {
            candidate = null
            consecutiveSamples = 0
            return null
        }
        if (candidate == packageName) {
            consecutiveSamples++
        } else {
            candidate = packageName
            consecutiveSamples = 1
        }
        if (consecutiveSamples < REQUIRED_SAMPLES || packageName == lastEmitted) return null
        lastEmitted = packageName
        return packageName
    }

    companion object {
        const val REQUIRED_SAMPLES = 2
    }
}
