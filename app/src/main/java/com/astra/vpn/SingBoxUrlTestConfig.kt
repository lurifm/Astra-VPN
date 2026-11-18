package com.astra.vpn

import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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
    const val GROUP_TAG = "astra-urltest"
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    data class Result(
        val json: String,
        /** Opaque sing-box outbound tag -> original profile URI. Never log this map. */
        val profileByOutboundTag: Map<String, String>
    )

    fun build(profileLinks: List<String>): Result {
        val profiles = profileLinks.map(String::trim).filter(String::isNotEmpty).distinct()
        require(profiles.isNotEmpty()) { "Нет серверов для проверки задержки" }

        val outbounds = JSONArray()
        val tags = JSONArray()
        val profileMap = linkedMapOf<String, String>()

        profiles.forEach { link ->
            // Reuse the same protocol parser and validation as normal connection setup.
            val singleConfig = JSONObject(SingBoxConfigBuilder.build(link))
            val proxy = singleConfig.getJSONArray("outbounds").getJSONObject(0)
            val tag = outboundTag(link)
            require(!profileMap.containsKey(tag)) { "Коллизия идентификаторов серверов" }
            proxy.put("tag", tag)
            outbounds.put(proxy)
            tags.put(tag)
            profileMap[tag] = link
        }

        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        outbounds.put(
            JSONObject()
                .put("type", "urltest")
                .put("tag", GROUP_TAG)
                .put("outbounds", tags)
                .put("url", TEST_URL)
                .put("interval", "30m")
                .put("tolerance", 0)
                .put("idle_timeout", "30m")
                .put("interrupt_exist_connections", false)
        )

        // This is deliberately not a VPN/TUN config. It is a short-lived
        // measurement engine and must never establish a second device-wide VPN.
        val config = JSONObject()
            .put("log", JSONObject().put("level", "error"))
            .put("outbounds", outbounds)
            .put("route", JSONObject()
                .put("auto_detect_interface", true)
                .put("final", "direct"))
            .toString()

        // Validate with the exact libbox version bundled in this application.
        Libbox.checkConfig(config)
        return Result(config, profileMap)
    }

    private fun outboundTag(link: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(link.toByteArray(Charsets.UTF_8))
        val suffix = digest.take(8).joinToString("") { "%02x".format(it) }
        return "astra-node-$suffix"
    }
}
