package com.astra.vpn

import android.net.Network
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** End-to-end checks for popular services, routed through the supplied network. */
object ServiceReachabilityChecker {
    data class Target(val name: String, val url: String)
    data class Result(val name: String, val httpCode: Int?, val latencyMs: Long?, val error: String?) {
        // Any HTTP response proves that the remote host was reached, even for 4xx/5xx.
        val reachable: Boolean get() = httpCode != null
        // A reachable service is usable only when it returns a success or redirect response.
        val serviceAvailable: Boolean get() = httpCode?.let(::isSuccessfulHttpCode) == true

        fun display(): String = when {
            serviceAvailable -> "$name: доступен · HTTP $httpCode · $latencyMs мс"
            httpCode != null -> "$name: сервер ответил · HTTP $httpCode · $latencyMs мс (сервис недоступен)"
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
     * Run independent service checks concurrently. Sequential checks could take
     * up to 24 seconds (three targets × connect/read timeouts), delaying diagnostics.
     * Keep a hard overall deadline and preserve the configured target order.
     */
    fun checkAll(network: Network): List<Result> {
        val executor = Executors.newFixedThreadPool(targets.size)
        return try {
            val tasks = targets.map { target -> Callable { check(network, target) } }
            val futures = executor.invokeAll(tasks, CHECK_ALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            futures.mapIndexed { index, future ->
                if (future.isCancelled) {
                    Result(targets[index].name, null, null, "TimeoutException")
                } else {
                    runCatching { future.get() }.getOrElse { error ->
                        Result(targets[index].name, null, null, error.cause?.javaClass?.simpleName
                            ?: error.javaClass.simpleName)
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            targets.map { Result(it.name, null, null, "InterruptedException") }
        } finally {
            executor.shutdownNow()
        }
    }

    const val CHECK_ALL_TIMEOUT_MS = 9_000L
}
