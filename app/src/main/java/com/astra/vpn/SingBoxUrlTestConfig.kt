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
    private const val GROUP_TAG_PREFIX = "astra-test-"
    const val TEST_URL = ServiceProbeUrlPolicy.DEFAULT_URL

    data class Result(
        val json: String,
        /** Opaque sing-box outbound tag -> original profile URI. Never log this map. */
        val profileByOutboundTag: Map<String, String>
    )

    fun build(profileLinks: List<String>, testUrl: String = TEST_URL): Result {
        val validatedTestUrl = ServiceProbeUrlPolicy.validate(testUrl)
        val profiles = profileLinks.map(String::trim).filter(String::isNotEmpty).distinct()
        require(profiles.isNotEmpty()) { "Нет серверов для проверки задержки" }

        val outbounds = JSONArray()
        val profileMap = linkedMapOf<String, String>()

        profiles.forEach { link ->
            // Reuse the same protocol parser and validation as normal connection setup.
            val proxy = SingBoxConfigBuilder.buildProxyOutbound(link)
            val tag = outboundTag(link)
            require(!profileMap.containsKey(tag)) { "Коллизия идентификаторов серверов" }
            proxy.put("tag", tag)
            outbounds.put(proxy)
            profileMap[tag] = link
            // A separate group gives every proxy its own URLTest lifecycle.
            outbounds.put(
                JSONObject()
                    .put("type", "urltest")
                    .put("tag", groupTagForOutbound(tag))
                    .put("outbounds", JSONArray().put(tag))
                    .put("url", validatedTestUrl)
                    .put("interval", "30m")
                    .put("tolerance", 0)
                    .put("idle_timeout", "30m")
                    .put("interrupt_exist_connections", false)
            )
        }

        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

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

    fun groupTagForOutbound(outboundTag: String): String {
        val suffix = outboundTag.removePrefix("astra-node-")
        require(outboundTag.startsWith("astra-node-") && suffix.matches(OUTBOUND_SUFFIX))
        { "Некорректный тег сервера" }
        return GROUP_TAG_PREFIX + suffix
    }

    fun outboundTagForGroup(groupTag: String): String? {
        if (!groupTag.startsWith(GROUP_TAG_PREFIX)) return null
        val suffix = groupTag.removePrefix(GROUP_TAG_PREFIX)
        if (!suffix.matches(OUTBOUND_SUFFIX)) return null
        return "astra-node-$suffix"
    }

    private fun outboundTag(link: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(link.toByteArray(Charsets.UTF_8))
        val suffix = digest.take(8).joinToString("") { "%02x".format(it) }
        return "astra-node-$suffix"
    }

    private val OUTBOUND_SUFFIX = Regex("[0-9a-f]{16}")
}
