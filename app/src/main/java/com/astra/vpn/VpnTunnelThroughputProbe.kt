package com.astra.vpn

import android.net.Network
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Measures download throughput on the supplied Android Network.
 * The caller passes the active VPN Network, not device-wide counters.
 */
internal object VpnTunnelThroughputProbe {
    private const val ENDPOINT = "https://speed.cloudflare.com/__down?bytes=524288"
    private const val EXPECTED_BYTES = 512L * 1024L
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val TOTAL_TIMEOUT_NANOS = 20_000_000_000L

    fun measure(network: Network, clockNanos: () -> Long = System::nanoTime): Double? {
        var connection: HttpsURLConnection? = null
        return try {
            connection = network.openConnection(URL(ENDPOINT)) as HttpsURLConnection
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
            if (contentLength >= 0L && contentLength != EXPECTED_BYTES) return null
            var received = 0L
            val buffer = ByteArray(16 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    if (clockNanos() - startedAt > TOTAL_TIMEOUT_NANOS) return null
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    received += count
                    if (received > EXPECTED_BYTES) return null
                }
            }
            ThroughputMeasurement.fromTransfer(
                bytesRead = received,
                expectedBytes = EXPECTED_BYTES,
                durationNanos = clockNanos() - startedAt
            )?.downloadMbps
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
