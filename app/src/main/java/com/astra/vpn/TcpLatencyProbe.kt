package com.astra.vpn

import android.net.Network
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Measures DNS resolution separately from TCP connection establishment.
 * TCP value is the median of five real TCP connection attempts (successful attempts only); it is not
 * an ICMP ping or a measurement through the VPN tunnel.
 */
object TcpLatencyProbe {
    data class Result(
        val dnsMs: Long,
        val tcpMedianMs: Long,
        val successfulSamples: Int
    )

    const val SAMPLE_COUNT = 5
    private const val CONNECT_TIMEOUT_MS = 1200

    fun measure(host: String, port: Int, network: Network? = null): Result {
        require(host.isNotBlank()) { "Host is empty" }
        require(port in 1..65535) { "Invalid port" }

        val dnsStarted = System.nanoTime()
        val addresses = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        val socketFactory = network?.socketFactory ?: SocketFactory.getDefault()
        val dnsMs = elapsedMillis(dnsStarted)
        if (addresses.isEmpty()) throw IllegalStateException("DNS returned no addresses")

        // A hostname may resolve to both IPv4 and IPv6. The first address
        // is not necessarily reachable on the current network, so try each
        // address until one completes at least one TCP connection.
        for (address in addresses) {
            val samples = mutableListOf<Long>()
            repeat(SAMPLE_COUNT) {
                val started = System.nanoTime()
                val connected = runCatching {
                    socketFactory.createSocket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                    }
                }.isSuccess
                if (connected) samples += elapsedMillis(started)
            }
            if (samples.isNotEmpty()) {
                return Result(dnsMs, median(samples), samples.size)
            }
        }
        throw IllegalStateException("TCP endpoint is unreachable")
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
