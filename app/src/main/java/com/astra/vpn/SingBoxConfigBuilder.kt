package com.astra.vpn

import android.net.Uri
import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts imported share links into the JSON format consumed by sing-box.
 * Unsupported or incomplete links fail closed instead of creating a misleading profile.
 */
object SingBoxConfigBuilder {

    fun build(serverLink: String, options: SingBoxAdvancedOptions = SingBoxAdvancedOptions()): String {
        val outbound = buildProxyOutbound(serverLink, options)

        val outbounds = JSONArray()
            .put(outbound)
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject()
                .put("servers", JSONArray()
                    .put(JSONObject()
                        .put("type", "https")
                        .put("tag", "dns-remote")
                        .put("server", "1.1.1.1")
                        .put("server_port", 443)
                        .put("path", "/dns-query")
                        .put("tls", JSONObject()
                            .put("enabled", true)
                            .put("server_name", "cloudflare-dns.com"))
                        )
                    .put(JSONObject()
                        .put("type", "local")
                        .put("tag", "dns-direct")))
                .put("final", "dns-remote")
                .put("independent_cache", options.dnsCacheEnabled))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                .put("mtu", options.safeMtu)
                .put("auto_route", true)
                .put("stack", "system")))
            .put("outbounds", outbounds)
            .put("route", JSONObject()
                .put("auto_detect_interface", true)
                .put("final", "proxy")
                .put("rules", JSONArray()
                    .put(JSONObject()
                        .put("protocol", "dns")
                        .put("action", "hijack-dns"))
                    .apply {
                        val directSuffixes = options.directDomainSuffixes()
                        if (directSuffixes.isNotEmpty()) {
                            put(JSONObject()
                                .put("domain_suffix", JSONArray(directSuffixes))
                                .put("outbound", "direct"))
                        }
                    }))
        val json = config.toString()
        // Validate against the exact sing-box core bundled in this APK before starting the VPN.
        Libbox.checkConfig(json)
        return json
    }

    /** Builds only the proxy outbound, without constructing/validating an entire VPN config. */
    internal fun buildProxyOutbound(
        serverLink: String,
        options: SingBoxAdvancedOptions = SingBoxAdvancedOptions()
    ): JSONObject {
        val uri = Uri.parse(serverLink.trim())
        val scheme = uri.scheme?.lowercase()
            ?: error("У ссылки сервера отсутствует протокол")
        val server = uri.host ?: error("В ссылке не указан адрес сервера")
        val port = uri.port.takeIf { it in 1..65535 }
            ?: error("В ссылке указан неверный порт")

        val outbound = when (scheme) {
            "vless" -> buildVless(uri, server, port)
            "hysteria2", "hy2" -> buildHysteria2(uri, server, port)
            "tuic" -> buildTuic(uri, server, port)
            else -> error("Пока не поддерживается запуск протокола: $scheme")
        }.put("tag", "proxy")
        if (options.shouldEnableMultiplex(scheme)) {
            outbound.put("multiplex", JSONObject()
                .put("enabled", true)
                .put("protocol", "smux")
                .put("max_connections", 4)
                .put("min_streams", 4))
        }
        return outbound
    }

    private fun buildVless(uri: Uri, server: String, port: Int): JSONObject {
        val userInfo = uri.userInfo?.let(Uri::decode)
        val uuid = userInfo?.substringBefore(':')?.takeIf { it.isNotBlank() }
            ?: error("В ссылке VLESS отсутствует UUID")
        val security = uri.getQueryParameter("security").orEmpty().lowercase()
        val tls = security == "tls" || security == "reality"
        val outbound = JSONObject()
            .put("type", "vless")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)

        uri.getQueryParameter("flow")?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }

        if (tls) {
            val tlsOptions = JSONObject()
                .put("enabled", true)
                .put("server_name", uri.getQueryParameter("sni") ?: server)
                .put("utls", JSONObject().put("enabled", true)
                    .put("fingerprint", uri.getQueryParameter("fp") ?: "chrome"))
            if (security == "reality") {
                val publicKey = uri.getQueryParameter("pbk")
                    ?: error("В Reality-ссылке отсутствует публичный ключ")
                tlsOptions.put("reality", JSONObject()
                    .put("enabled", true)
                    .put("public_key", publicKey)
                    .put("short_id", uri.getQueryParameter("sid") ?: ""))
            }
            outbound.put("tls", tlsOptions)
        }

        when ((uri.getQueryParameter("type") ?: "tcp").lowercase()) {
            "tcp" -> Unit
            "ws" -> outbound.put("transport", JSONObject()
                .put("type", "ws")
                .put("path", uri.getQueryParameter("path") ?: "/")
                .put("headers", JSONObject().put("Host", uri.getQueryParameter("host") ?: server)))
            "grpc" -> outbound.put("transport", JSONObject()
                .put("type", "grpc")
                .put("service_name", uri.getQueryParameter("serviceName")
                    ?: uri.getQueryParameter("service_name") ?: ""))
            else -> error("Этот тип транспорта VLESS пока не поддерживается")
        }
        return outbound
    }

    private fun buildHysteria2(uri: Uri, server: String, port: Int): JSONObject {
        val userInfo = uri.userInfo?.let(Uri::decode)
        val password = userInfo?.substringAfter(':', userInfo)
            ?.takeIf { it.isNotBlank() } ?: error("В Hysteria2-ссылке отсутствует пароль")
        val outbound = JSONObject()
            .put("type", "hysteria2")
            .put("server", server)
            .put("server_port", port)
            .put("password", password)
            .put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", uri.getQueryParameter("sni") ?: server)
                .put("insecure", uri.getQueryParameter("insecure").isTruthy()))
        if (uri.getQueryParameter("obfs")?.lowercase() == "salamander") {
            val obfsPassword = uri.getQueryParameter("obfs-password")
                ?: error("Для Salamander не указан пароль обфускации")
            outbound.put("obfs", JSONObject().put("type", "salamander").put("password", obfsPassword))
        }
        return outbound
    }

    private fun String?.isTruthy(): Boolean =
        this?.lowercase() in setOf("1", "true", "yes")

    private fun buildTuic(uri: Uri, server: String, port: Int): JSONObject {
        val credentials = uri.userInfo?.let(Uri::decode)
            ?: error("В TUIC-ссылке отсутствуют данные входа")
        val uuid = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        require(uuid.isNotBlank() && password.isNotBlank()) { "В TUIC-ссылке неверные данные входа" }
        return JSONObject()
            .put("type", "tuic")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("password", password)
            .put("congestion_control", uri.getQueryParameter("congestion_control") ?: "bbr")
            .put("udp_relay_mode", uri.getQueryParameter("udp_relay_mode") ?: "native")
            .put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", uri.getQueryParameter("sni") ?: server)
                .put("insecure", uri.getQueryParameter("insecure").isTruthy())
                .put("alpn", JSONArray().put("h3")))
    }
}
