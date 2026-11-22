package com.astra.vpn

/** Removes imported proxy URIs and inline credentials from native diagnostics. */
internal object DiagnosticSanitizer {
    private val profileUri = Regex("(?i)(vless|vmess|trojan|ss|hysteria2?|tuic)://\\S+")
    private val inlineCredentials = Regex("(?i)(://)[^/@\\s]+@")

    fun sanitize(message: String): String = message
        .replace(profileUri, "<profile-uri-redacted>")
        .replace(inlineCredentials, "$1<credentials-redacted>@")
}
