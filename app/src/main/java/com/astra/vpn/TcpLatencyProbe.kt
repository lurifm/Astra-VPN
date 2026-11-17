package com.astra.vpn

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures DNS resolution separately from TCP connection establishment.
 * TCP value is the median of successful short connection probes; it is not
 * an ICMP ping or a measurement through the VPN tunnel.
 */
object TcpLatencyProbe {
    data class Result(
        val dnsMs: Long,
        val tcpMedianMs: Long,
        val successfulSamples: Int
    )

    const val SAMPLE_COUNT = 3
    private const val CONNECT_TIMEOUT_MS = 1500

    fun measure(host: String, port: Int): Result {
        require(host.isNotBlank()) { "Host is empty" }
        require(port in 1..65535) { "Invalid port" }

        val dnsStarted = System.nanoTime()
        val address = InetAddress.getAllByName(host).firstOrNull()
            ?: throw IllegalStateException("DNS returned no addresses")
        val dnsMs = elapsedMillis(dnsStarted)

        val samples = mutableListOf<Long>()
        repeat(SAMPLE_COUNT) {
            val started = System.nanoTime()
            val connected = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                }
            }.isSuccess
            if (connected) samples += elapsedMillis(started)
        }
        if (samples.isEmpty()) throw IllegalStateException("TCP endpoint is unreachable")
        return Result(dnsMs, median(samples), samples.size)
    }

    fun median(values: List<Long>): Long {
        require(values.isNotEmpty()) { "At least one measurement is required" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(1)
}
