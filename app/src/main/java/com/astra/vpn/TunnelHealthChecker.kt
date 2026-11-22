package com.astra.vpn

import java.net.URL
import android.net.Network
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

    // Redirects may be captive-portal/login pages, not proof of unrestricted Internet access.
    internal fun isReachableStatusCode(code: Int): Boolean = code in 200..299

    fun check(vpnNetwork: Network): Boolean {
        // One total budget covers both fallback endpoints. Independent connect/read
        // limits could otherwise take ~14s when both endpoints stall.
        val deadline = System.nanoTime() / 1_000_000L + TOTAL_TIMEOUT_MS
        for ((index, endpoint) in endpoints.withIndex()) {
            val remaining = deadline - System.nanoTime() / 1_000_000L
            val phaseTimeout = TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(
                remainingMs = remaining,
                endpointsRemaining = endpoints.size - index
            )
            if (phaseTimeout <= 0) return false
            val reachable = runCatching {
                val connection = (vpnNetwork.openConnection(URL(endpoint)) as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = phaseTimeout
                    readTimeout = phaseTimeout
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Connection", "close")
                }
                try {
                    val code = connection.responseCode
                    isReachableStatusCode(code)
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (reachable) return true
        }
        return false
    }
    private const val TOTAL_TIMEOUT_MS = 10_000L
}


/** Splits one remaining wall-clock budget across connect/read phases and fallback endpoints. */
internal object TunnelHealthTimeoutPolicy {
    fun timeoutPerPhaseMs(remainingMs: Long, endpointsRemaining: Int): Int {
        if (remainingMs <= 0L || endpointsRemaining <= 0) return 0
        val phasesRemaining = endpointsRemaining.toLong() * 2L
        return (remainingMs / phasesRemaining).coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
