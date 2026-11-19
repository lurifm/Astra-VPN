package com.astra.vpn

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Downloads a fixed, bounded payload through one local per-server HTTP proxy.
 * The endpoint is intentionally fixed to prevent arbitrary URL/SSRF probing.
 */
internal object ThroughputDownloadProbe {
    const val ENDPOINT = "https://speed.cloudflare.com/__down"
    const val DEFAULT_BYTES = 4L * 1024L * 1024L
    const val MIN_BYTES = ThroughputMeasurement.DEFAULT_MIN_BYTES
    const val MAX_BYTES = ThroughputMeasurement.DEFAULT_MAX_BYTES
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val TOTAL_TIMEOUT_NANOS = 45_000_000_000L

    fun measure(
        proxyPort: Int,
        expectedBytes: Long = DEFAULT_BYTES,
        connectionFactory: (URL, Proxy) -> HttpsURLConnection = { url, proxy ->
            url.openConnection(proxy) as HttpsURLConnection
        },
        clockNanos: () -> Long = System::nanoTime
    ): ThroughputMeasurement.Sample? {
        if (proxyPort !in 1..65535) return null
        if (expectedBytes !in MIN_BYTES..MAX_BYTES) return null

        val url = URL("$ENDPOINT?bytes=$expectedBytes")
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
        var connection: HttpsURLConnection? = null
        return try {
            connection = connectionFactory(url, proxy)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")

            val startedAt = clockNanos()
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return null
            val contentLength = connection.contentLengthLong
            if (contentLength >= 0L && contentLength != expectedBytes) return null

            var received = 0L
            val buffer = ByteArray(32 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    if (clockNanos() - startedAt > TOTAL_TIMEOUT_NANOS) return null
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    received += count
                    if (received > expectedBytes) return null
                }
            }
            ThroughputMeasurement.fromTransfer(
                bytesRead = received,
                expectedBytes = expectedBytes,
                durationNanos = clockNanos() - startedAt
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
