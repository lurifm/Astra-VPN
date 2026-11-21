package com.astra.vpn

import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a short-lived sing-box engine configuration for a throughput probe.
 *
 * A local mixed inbound accepts only loopback HTTP proxy traffic. Its route rule
 * pins every request to exactly one imported proxy outbound. No TUN inbound is
 * created, so this engine cannot replace or capture the user's active VPN.
 */
internal object SingBoxThroughputTestConfig {
    const val INBOUND_TAG = "astra-throughput-in"
    const val OUTBOUND_TAG = "astra-throughput-proxy"
    const val LISTEN_ADDRESS = "127.0.0.1"

    data class Result(
        val json: String,
        val proxyHost: String,
        val proxyPort: Int
    )

    fun build(profileLink: String, listenPort: Int): Result {
        require(listenPort in 1..65535) { "Некорректный локальный порт теста" }

        val source = JSONObject(SingBoxConfigBuilder.build(profileLink))
        val sourceOutbounds = source.getJSONArray("outbounds")
        require(sourceOutbounds.length() >= 1) { "В профиле отсутствует proxy outbound" }

        // Copy only the proxy outbound. A direct fallback would hide routing errors
        // and could accidentally benchmark the user's physical connection instead.
        val proxy = JSONObject(sourceOutbounds.getJSONObject(0).toString())
            .put("tag", OUTBOUND_TAG)
        val outbounds = JSONArray()
            .put(proxy)
            .put(JSONObject().put("type", "direct").put("tag", "direct"))

        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", INBOUND_TAG)
            .put("listen", LISTEN_ADDRESS)
            .put("listen_port", listenPort)

        val dns = source.optJSONObject("dns")
        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("final", "direct")
            .put("rules", JSONArray().put(
                JSONObject()
                    .put("inbound", JSONArray().put(INBOUND_TAG))
                    .put("outbound", OUTBOUND_TAG)
            ))

        val config = JSONObject()
            .put("log", JSONObject().put("level", "error"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
            .put("route", route)
        if (dns != null) config.put("dns", dns)

        val json = config.toString()
        // Validate using the exact libbox core bundled with the application.
        Libbox.checkConfig(json)
        return Result(json, LISTEN_ADDRESS, listenPort)
    }
}
