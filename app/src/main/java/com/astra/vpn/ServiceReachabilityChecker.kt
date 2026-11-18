package com.astra.vpn

import android.net.Network
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** End-to-end checks for popular services, routed through the supplied network. */
object ServiceReachabilityChecker {
    data class Target(val name: String, val url: String)
    data class Result(val name: String, val httpCode: Int?, val latencyMs: Long?, val error: String?) {
        val reachable: Boolean get() = httpCode != null && httpCode in 200..399
        fun display(): String = when {
            reachable -> "$name: доступен · HTTP $httpCode · $latencyMs мс"
            httpCode != null -> "$name: ответ HTTP $httpCode · $latencyMs мс"
            else -> "$name: ошибка соединения ($error)"
        }
    }

    val targets = listOf(
        Target("YouTube", "https://www.youtube.com/generate_204"),
        Target("Telegram", "https://telegram.org/"),
        Target("ChatGPT", "https://chatgpt.com/")
    )

    fun isSuccessfulHttpCode(code: Int): Boolean = code in 200..399

    fun check(network: Network, target: Target): Result {
        val startedAt = System.nanoTime()
        return try {
            val connection = (network.openConnection(URL(target.url)) as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "AstraVPN/1.0")
            }
            try {
                val code = connection.responseCode
                Result(target.name, code, (System.nanoTime() - startedAt) / 1_000_000L, null)
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            // Do not expose hostnames, proxy credentials or raw exception messages.
            Result(target.name, null, null, error.javaClass.simpleName)
        }
    }

    fun checkAll(network: Network): List<Result> = targets.map { check(network, it) }
}
