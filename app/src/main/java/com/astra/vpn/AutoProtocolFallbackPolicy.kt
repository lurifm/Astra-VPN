package com.astra.vpn

import java.util.Locale

/**
 * When a tunnel repeatedly fails end-to-end checks, try a different transport
 * before retrying another profile that uses the same protocol.
 */
internal object AutoProtocolFallbackPolicy {
    private val supportedProtocols = setOf("vless", "hysteria2", "tuic")

    private fun protocol(link: String): String? {
        val raw = link.substringBefore("://", "").lowercase(Locale.ROOT)
        val normalized = if (raw == "hy2") "hysteria2" else raw
        return normalized.takeIf { it in supportedProtocols }
    }

    /**
     * Prefer a measured profile on a different transport after repeated tunnel
     * failures. If no alternative transport has a fresh successful measurement,
     * keep the other measured profiles as a fallback.
     */
    fun candidatesWithFreshMeasurements(
        profiles: List<String>,
        currentProfile: String?,
        measuredProfiles: Set<String>
    ): List<String> {
        val measured = profiles.filter { it in measuredProfiles }
        if (measured.isEmpty()) return emptyList()
        val currentProtocol = currentProfile?.let(::protocol) ?: return measured
        val alternatives = measured.filter { candidate ->
            protocol(candidate)?.let { it != currentProtocol } == true
        }
        return alternatives.ifEmpty { measured }
    }
}
