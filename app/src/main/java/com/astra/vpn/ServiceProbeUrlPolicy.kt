package com.astra.vpn

/**
 * Explicit allowlist for per-profile service probes. A probe URL is configuration,
 * not arbitrary user input: never let imported text turn the isolated test engine
 * into a generic URL fetcher.
 */
internal object ServiceProbeUrlPolicy {
    const val DEFAULT_URL = "https://www.gstatic.com/generate_204"
    private val allowed = setOf(
        DEFAULT_URL,
        "https://www.youtube.com/generate_204",
        "https://telegram.org/",
        "https://chatgpt.com/",
        "https://www.netflix.com/"
    )

    fun validate(url: String): String {
        val normalized = url.trim()
        require(normalized in allowed) { "URL проверки сервиса не входит в allowlist" }
        return normalized
    }
}
