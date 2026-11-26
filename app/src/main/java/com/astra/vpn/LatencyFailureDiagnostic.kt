package com.astra.vpn

/**
 * Produces a compact diagnostic for latency startup failures.
 * Native messages are included only after redacting proxy URIs and credentials.
 */
internal object LatencyFailureDiagnostic {
    fun describe(failure: Throwable, maxCauses: Int = 3): String {
        require(maxCauses >= 1)
        val details = mutableListOf<String>()
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var current: Throwable? = failure
        while (current != null && details.size < maxCauses && seen.add(current)) {
            val type = current.javaClass.simpleName.ifBlank { current.javaClass.name }
            val safeMessage = current.message
                ?.takeIf { it.isNotBlank() }
                ?.let(DiagnosticSanitizer::sanitize)
                ?.replace(Regex("\\s+"), " ")
                ?.take(MAX_MESSAGE_LENGTH)
            details += if (safeMessage == null) type else "$type: $safeMessage"
            current = current.cause
        }
        return details.joinToString(" <- ").take(MAX_DIAGNOSTIC_LENGTH)
    }

    private const val MAX_MESSAGE_LENGTH = 180
    private const val MAX_DIAGNOSTIC_LENGTH = 600
}
