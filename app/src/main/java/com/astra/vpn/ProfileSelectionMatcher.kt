package com.astra.vpn

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Resolves a selected profile after a subscription rotates credentials or URI options.
 * Credentials and query parameters are intentionally excluded from identity.
 */
object ProfileSelectionMatcher {
    private data class Identity(
        val protocol: String,
        val host: String,
        val port: Int,
        val name: String
    ) {
        fun endpointKey() = "$protocol|$host|$port"
        fun namedKey() = "$protocol|$name"
    }

    fun remap(previousLink: String?, candidates: List<String>): String? {
        if (previousLink == null) return null
        if (previousLink in candidates) return previousLink
        val previous = identity(previousLink) ?: return null
        val parsed = candidates.mapNotNull { link -> identity(link)?.let { link to it } }
        val endpointMatches = parsed.filter { it.second.endpointKey() == previous.endpointKey() }
        if (endpointMatches.size == 1) return endpointMatches.single().first
        if (endpointMatches.size > 1) return null

        // A name-only fallback is safe only when unique in both old and new identities.
        if (previous.name.isBlank()) return null
        val namedMatches = parsed.filter { it.second.namedKey() == previous.namedKey() }
        return namedMatches.singleOrNull()?.first
    }

    private fun identity(link: String): Identity? = runCatching {
        val uri = URI(link.trim())
        val protocol = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "hy2" -> "hysteria2"
            "vless", "hysteria2", "tuic" -> uri.scheme.lowercase(Locale.ROOT)
            else -> return null
        }
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val name = uri.rawFragment?.let {
            URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }?.trim()?.lowercase(Locale.ROOT).orEmpty()
        Identity(protocol, host, port, name)
    }.getOrNull()
}
