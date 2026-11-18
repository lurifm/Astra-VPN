package com.astra.vpn

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Verifies that traffic can actually leave the device through the active VPN.
 * This is intentionally separate from VpnService/TUN state: Android can report
 * an established VPN interface even when the remote proxy cannot reach the web.
 *
 * The app's sockets use Android's default routing, which is the VPN route while
 * Astra is active. No server credentials or subscription URLs are sent here.
 */
object TunnelHealthChecker {
    private val endpoints = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204"
    )

    fun check(): Boolean {
        for (endpoint in endpoints) {
            val reachable = runCatching {
                val connection = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3_500
                    readTimeout = 3_500
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Connection", "close")
                }
                try {
                    val code = connection.responseCode
                    code == HttpURLConnection.HTTP_NO_CONTENT ||
                        code == HttpURLConnection.HTTP_OK
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (reachable) return true
        }
        return false
    }
}
