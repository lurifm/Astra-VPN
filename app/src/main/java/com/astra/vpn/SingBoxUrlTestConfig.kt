package com.astra.vpn

import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Builds a temporary, non-TUN sing-box instance configuration for measuring
 * each imported proxy with sing-box URLTest. The test itself is an HTTP request
 * through each outbound, so UDP-based protocols are measured by their real
 * transport rather than by an unrelated TCP port probe.
 *
 * This class only builds and validates the config. Starting/stopping the
 * isolated test engine and collecting CommandClient URLTest results are kept
 * in a separate lifecycle component so a probe can never disturb the active VPN.
 */
internal object SingBoxUrlTestConfig {
    data class Result(
        val json: String,
        /** Opaque sing-box outbound tag -> original profile URI. Never log this map. */
        val profileByOutboundTag: Map<String, String>
    )

    fun build(profileLinks: List<String>): Result {
        val profiles = profileLinks.map(String::trim).filter(String::isNotEmpty).distinct()
        require(profiles.isNotEmpty()) { "Нет серверов для проверки задержки" }

        val outbounds = JSONArray()
        val profileMap = linkedMapOf<String, String>()
        // URLTest history is stored by outbound tag inside libbox. A stable
        // profile-only tag lets a new measurement session inherit an old delay
        // in the initial SubscribeOutbounds snapshot. Scope tags to this engine
        // instance so only measurements produced in this session can be observed.
        val sessionNonce = UUID.randomUUID().toString()

        profiles.forEach { link ->
            // Keep these as plain proxy outbounds. sing-box starts an automatic
            // background check for every URLTest group at engine startup; that
            // would bypass Astra's four-at-a-time scheduler. CommandClient.URLTest
            // can trigger an individual plain outbound on demand instead.
            val proxy = SingBoxConfigBuilder.buildProxyOutbound(link)
            val tag = outboundTag(link, sessionNonce)
            require(!profileMap.containsKey(tag)) { "Коллизия идентификаторов серверов" }
            proxy.put("tag", tag)
            outbounds.put(proxy)
            profileMap[tag] = link
        }

        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        // This is deliberately not a VPN/TUN config. It is a short-lived
        // measurement engine and must never establish a second device-wide VPN.
        // Resolve proxy endpoint hostnames and the URL-test host through an
        // explicit DoH resolver. Android's process-default DNS route can still
        // point at the active VPN even when libbox sockets use the physical
        // underlay; that mismatch makes otherwise-working profiles time out.
        val probeDns = JSONObject()
            .put("type", "https")
            .put("tag", "probe-dns")
            // IP literal avoids needing DNS to resolve the DNS provider itself.
            // TLS SNI remains the provider hostname for certificate validation.
            .put("server", "1.1.1.1")
            .put("server_port", 443)
            .put("path", "/dns-query")
            .put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", "cloudflare-dns.com"))
            // This DNS connection must leave through the physical interface,
            // never through a tested proxy outbound or the active VPN tunnel.
            .put("detour", "direct")

        val config = JSONObject()
            .put("log", JSONObject().put("level", "debug"))
            .put("dns", JSONObject()
                .put("servers", JSONArray().put(probeDns))
                .put("final", "probe-dns")
                .put("strategy", "prefer_ipv4"))
            .put("outbounds", outbounds)
            .put("route", JSONObject()
                .put("auto_detect_interface", true)
                .put("default_domain_resolver", "probe-dns")
                .put("final", "direct"))
            .toString()

        // Validate with the exact libbox version bundled in this application.
        Libbox.checkConfig(config)
        return Result(config, profileMap)
    }

    private fun outboundTag(link: String, sessionNonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sessionNonce\u0000$link".toByteArray(Charsets.UTF_8))
        val suffix = digest.take(8).joinToString("") { "%02x".format(it) }
        return "astra-node-$suffix"
    }

    // Retained for compatibility with existing tag-mapping tests and callers.
    // The latency scheduler now probes plain outbound tags directly.
    fun groupTagForOutbound(outboundTag: String): String {
        val suffix = outboundTag.removePrefix("astra-node-")
        require(outboundTag.startsWith("astra-node-") && suffix.matches(OUTBOUND_SUFFIX))
        { "Некорректный тег сервера" }
        return "astra-test-$suffix"
    }

    fun outboundTagForGroup(groupTag: String): String? {
        if (!groupTag.startsWith("astra-test-")) return null
        val suffix = groupTag.removePrefix("astra-test-")
        if (!suffix.matches(OUTBOUND_SUFFIX)) return null
        return "astra-node-$suffix"
    }

    private val OUTBOUND_SUFFIX = Regex("[0-9a-f]{16}")
}
