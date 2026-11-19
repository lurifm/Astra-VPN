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
     * Keeps all eligible profiles, but places profiles using another supported
     * protocol first. If no cross-protocol candidate exists, preserves the input.
     */
    fun preferAlternativeProtocol(profiles: List<String>, currentProfile: String?): List<String> {
        val currentProtocol = currentProfile?.let(::protocol) ?: return profiles
        val alternatives = profiles.filter { candidate ->
            protocol(candidate)?.let { it != currentProtocol } == true
        }
        return alternatives.ifEmpty { profiles }
    }
}
