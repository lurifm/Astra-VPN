package com.astra.vpn

import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Builds a temporary sing-box instance exposing one loopback HTTP proxy per
 * imported profile. Each inbound is routed to exactly one proxy outbound.
 *
 * The caller owns port allocation and the isolated service lifecycle. This
 * config deliberately contains no TUN inbound and cannot replace Android's VPN.
 */
internal object SingBoxThroughputTestConfig {
    data class Target(val profileLink: String, val listenPort: Int)
    data class Result(val json: String, val portByOutboundTag: Map<String, Int>)

    fun build(targets: List<Target>): Result {
        require(targets.isNotEmpty()) { "Нет серверов для измерения скорости" }
        require(targets.size <= MAX_TARGETS) { "Слишком много серверов для одного измерения" }
        require(targets.all { it.profileLink.isNotBlank() }) { "Пустая ссылка сервера" }
        require(targets.all { it.listenPort in 1..65535 }) { "Неверный порт локального прокси" }
        require(targets.map { it.listenPort }.distinct().size == targets.size) {
            "Порты локальных прокси должны быть уникальными"
        }

        val outbounds = JSONArray()
        val inbounds = JSONArray()
        val rules = JSONArray()
        val ports = linkedMapOf<String, Int>()
        val seenTags = mutableSetOf<String>()

        targets.forEachIndexed { index, target ->
            val parsed = JSONObject(SingBoxConfigBuilder.build(target.profileLink))
            val proxy = parsed.getJSONArray("outbounds").getJSONObject(0)
            val outboundTag = outboundTag(target.profileLink)
            require(seenTags.add(outboundTag)) { "Коллизия идентификаторов серверов" }
            val inboundTag = "astra-throughput-in-$index"
            proxy.put("tag", outboundTag)
            outbounds.put(proxy)
            inbounds.put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", inboundTag)
                    .put("listen", "127.0.0.1")
                    .put("listen_port", target.listenPort)
            )
            rules.put(
                JSONObject()
                    .put("inbound", JSONArray().put(inboundTag))
                    .put("action", "route")
                    .put("outbound", outboundTag)
            )
            ports[outboundTag] = target.listenPort
        }

        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        val config = JSONObject()
            .put("log", JSONObject().put("level", "error"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put(
                "route",
                JSONObject()
                    .put("auto_detect_interface", true)
                    .put("rules", rules)
                    .put("final", "direct")
            )
            .toString()

        // Validate against the exact libbox version bundled with the app.
        Libbox.checkConfig(config)
        return Result(config, ports)
    }

    private fun outboundTag(link: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(link.toByteArray(Charsets.UTF_8))
        val suffix = digest.take(8).joinToString("") { "%02x".format(it) }
        return "astra-throughput-node-$suffix"
    }

    private const val MAX_TARGETS = 64
}
