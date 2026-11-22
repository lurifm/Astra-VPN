package com.astra.vpn

import android.net.Network
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * Measures DNS resolution separately from TCP connection establishment.
 * TCP value is the median of five real TCP connection attempts (successful attempts only); it is not
 * an ICMP ping or a measurement through the VPN tunnel.
 *
 * The whole operation, including DNS and all address/sample attempts, is bounded by [timeoutMs].
 * DNS APIs may ignore thread interruption, so the worker is daemonized and the caller still returns
 * at the deadline. An in-flight socket is closed when the deadline expires.
 */
object TcpLatencyProbe {
    data class Result(
        val dnsMs: Long,
        val tcpMedianMs: Long,
        val successfulSamples: Int
    )

    const val SAMPLE_COUNT = 5
    const val DEFAULT_TIMEOUT_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 1200

    fun measure(
        host: String,
        port: Int,
        network: Network? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result {
        require(host.isNotBlank()) { "Host is empty" }
        require(port in 1..65535) { "Invalid port" }
        require(timeoutMs in 1L..60_000L) { "Timeout must be between 1 and 60000 ms" }

        val activeSocket = AtomicReference<Socket?>(null)
        val timedOut = AtomicBoolean(false)
        return runBounded(timeoutMs, onTimeout = {
            // DNS may ignore interruption and return after the caller's deadline.
            timedOut.set(true)
            runCatching { activeSocket.getAndSet(null)?.close() }
        }) {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val dnsStarted = System.nanoTime()
            val addresses = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
            if (timedOut.get()) throw SocketTimeoutException("TCP latency probe timed out during DNS")
            val socketFactory = network?.socketFactory ?: SocketFactory.getDefault()
            val dnsMs = elapsedMillis(dnsStarted)
            if (addresses.isEmpty()) throw IllegalStateException("DNS returned no addresses")

            // A hostname may resolve to both IPv4 and IPv6. Try each address, but never
            // let a slow address consume more than the remaining global probe budget.
            for (address in addresses) {
                val samples = mutableListOf<Long>()
                repeat(SAMPLE_COUNT) {
                    val remainingMs = remainingMillis(deadlineNanos)
                    if (timedOut.get() || remainingMs <= 0L) {
                        throw SocketTimeoutException("TCP latency probe timed out")
                    }
                    val started = System.nanoTime()
                    val socket = socketFactory.createSocket()
                    // Close the race where the timeout fires before socket publication.
                    if (!registerActiveSocket(activeSocket, socket, timedOut)) {
                        throw SocketTimeoutException("TCP latency probe timed out")
                    }
                    val connected = try {
                        socket.use {
                            it.connect(
                                InetSocketAddress(address, port),
                                minOf(CONNECT_TIMEOUT_MS.toLong(), remainingMs).toInt().coerceAtLeast(1)
                            )
                        }
                        true
                    } catch (_: Exception) {
                        false
                    } finally {
                        activeSocket.compareAndSet(socket, null)
                    }
                    if (connected) samples += elapsedMillis(started)
                }
                if (samples.isNotEmpty()) return@runBounded Result(dnsMs, median(samples), samples.size)
            }
            if (remainingMillis(deadlineNanos) <= 0L) {
                throw SocketTimeoutException("TCP latency probe timed out")
            }
            throw IllegalStateException("TCP endpoint is unreachable")
        }
    }

    internal fun <T> runBounded(
        timeoutMs: Long,
        onTimeout: () -> Unit = {},
        operation: () -> T
    ): T {
        require(timeoutMs in 1L..60_000L) { "Timeout must be between 1 and 60000 ms" }
        val task = FutureTask(operation)
        Thread(task, "Astra-TCP-latency").apply {
            isDaemon = true
            start()
        }
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            runCatching(onTimeout)
            throw SocketTimeoutException("TCP latency probe exceeded ${timeoutMs} ms")
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw IllegalStateException("TCP latency probe failed", cause)
        }
    }

    internal fun registerActiveSocket(
        activeSocket: AtomicReference<Socket?>,
        socket: Socket,
        timedOut: AtomicBoolean
    ): Boolean {
        if (timedOut.get()) {
            runCatching { socket.close() }
            return false
        }
        activeSocket.set(socket)
        if (timedOut.get()) {
            activeSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            return false
        }
        return true
    }

    internal fun remainingMillis(deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return 0L
        return ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    fun median(values: List<Long>): Long {
        require(values.isNotEmpty()) { "At least one measurement is required" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        if (sorted.size % 2 == 1) return sorted[middle]
        val lower = sorted[middle - 1]
        val upper = sorted[middle]
        // Avoid overflow when two large, valid measurements are averaged.
        return lower + (upper - lower) / 2L
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(1)
}
