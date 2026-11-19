package com.astra.vpn

import android.net.Network
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** End-to-end checks for popular services, routed through the supplied network. */
object ServiceReachabilityChecker {
    data class Target(val name: String, val url: String)
    data class Result(val name: String, val httpCode: Int?, val latencyMs: Long?, val error: String?) {
        val reachable: Boolean get() = httpCode?.let(::isSuccessfulHttpCode) == true
        fun display(): String = when {
            reachable -> "$name: доступен · HTTP $httpCode · $latencyMs мс"
            httpCode != null -> "$name: ответ HTTP $httpCode · $latencyMs мс"
            else -> "$name: ошибка соединения (${error ?: "неизвестная ошибка"})"
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

    /**
     * Run independent endpoints concurrently so a slow/unreachable service
     * does not make the diagnostics screen wait through three serial timeouts.
     * Each connection still has bounded connect/read timeouts; the batch also
     * has a global deadline and returns an explicit timeout result if needed.
     */
    fun checkAll(network: Network): List<Result> {
        if (targets.isEmpty()) return emptyList()
        val executor = Executors.newFixedThreadPool(targets.size.coerceAtMost(3))
        return try {
            val tasks = targets.map { target -> Callable { check(network, target) } }
            val futures = executor.invokeAll(tasks, 6, TimeUnit.SECONDS)
            futures.mapIndexed { index, future ->
                if (future.isCancelled) {
                    Result(targets[index].name, null, null, "TimeoutException")
                } else {
                    runCatching { future.get() }.getOrElse {
                        Result(targets[index].name, null, null, it.javaClass.simpleName)
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            targets.map { Result(it.name, null, null, "InterruptedException") }
        } finally {
            executor.shutdownNow()
        }
    }
}
