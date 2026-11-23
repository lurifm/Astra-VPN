package com.astra.vpn

/** Removes imported proxy URIs, subscription URLs and inline credentials from native diagnostics. */
internal object DiagnosticSanitizer {
    private val profileUri = Regex("(?i)(vless|vmess|trojan|ss|hysteria2?|tuic)://\\S+")
    private val inlineCredentials = Regex("(?i)(://)[^/@\\s]+@")
    // Native diagnostics may include subscription links with bearer tokens in the query,
    // or protocols not covered by profileUri. Redact every remaining absolute URI.
    private val absoluteUri = Regex("(?i)\\b[a-z][a-z0-9+.-]*://\\S+")

    fun sanitize(message: String): String = message
        .replace(profileUri, "<profile-uri-redacted>")
        .replace(inlineCredentials, "$1<credentials-redacted>@")
        .replace(absoluteUri, "<uri-redacted>")
}
