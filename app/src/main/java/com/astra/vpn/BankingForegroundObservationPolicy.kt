package com.astra.vpn

/**
 * Debounces UsageEvents-derived foreground observations.
 *
 * A package is emitted after two consecutive identical, non-null samples and
 * on every subsequent stable sample. Re-publishing stable observations lets
 * the executor retry a transiently rejected command (for example, auto-resume
 * after a previous service start failed). Ambiguous/null samples reset the
 * candidate but never emit a synthetic "bank closed" event.
 */
internal class BankingForegroundObservationPolicy {
    private var candidate: String? = null
    private var consecutiveSamples = 0

    fun reset() {
        candidate = null
        consecutiveSamples = 0
    }

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
        if (consecutiveSamples < REQUIRED_SAMPLES) return null
        return packageName
    }

    companion object {
        const val REQUIRED_SAMPLES = 2
    }
}
