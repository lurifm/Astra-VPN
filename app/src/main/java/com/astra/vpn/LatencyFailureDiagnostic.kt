package com.astra.vpn

/**
 * Produces a compact, secret-safe diagnostic for latency probe startup failures.
 * Exception messages are intentionally omitted because native/config errors can
 * contain server addresses, credentials, or subscription data.
 */
internal object LatencyFailureDiagnostic {
    fun describe(failure: Throwable, maxCauses: Int = 3): String {
        require(maxCauses >= 1)
        val names = mutableListOf<String>()
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var current: Throwable? = failure
        while (current != null && names.size < maxCauses && seen.add(current)) {
            names += current.javaClass.simpleName.ifBlank { current.javaClass.name }
            current = current.cause
        }
        return names.joinToString(" <- ")
    }
}
