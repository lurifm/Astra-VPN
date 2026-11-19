package com.astra.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Lightweight HTTPS reachability checks. These checks report whether a service
 * responds through the device's current default route; they are not a guarantee
 * that every feature of the service is available.
 */
internal object AstraServiceChecks {
    data class Result(val service: String, val reachable: Boolean, val latencyMs: Long?)

    private val endpoints = linkedMapOf(
        "YouTube" to "https://www.youtube.com/generate_204",
        "Telegram" to "https://telegram.org/",
        "ChatGPT" to "https://chatgpt.com/"
    )

    fun run(context: Context, callback: (List<Result>) -> Unit) {
        val appContext = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            val network: Network? = connectivity.activeNetwork
            val results = endpoints.map { (name, endpoint) ->
                val started = System.nanoTime()
                val ok = runCatching {
                    val connection = (network?.openConnection(URL(endpoint))
                        ?: URL(endpoint).openConnection()) as HttpURLConnection
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.instanceFollowRedirects = true
                    connection.requestMethod = "GET"
                    try {
                        connection.responseCode in 200..399
                    } finally {
                        connection.disconnect()
                    }
                }.getOrDefault(false)
                Result(name, ok, if (ok) (System.nanoTime() - started) / 1_000_000L else null)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(results) }
        }
    }
}
