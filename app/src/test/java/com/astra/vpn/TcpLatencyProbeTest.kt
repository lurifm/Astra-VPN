package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.InetAddress
import java.net.SocketAddress
import java.io.IOException
import javax.net.SocketFactory
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

class TcpLatencyProbeTest {
    @Test
    fun medianUsesMiddleSampleForOddSampleCount() {
        assertEquals(42L, TcpLatencyProbe.median(listOf(90L, 42L, 11L, 75L, 38L)))
    }

    @Test
    fun medianAveragesTwoMiddleSamplesForEvenSampleCount() {
        assertEquals(60L, TcpLatencyProbe.median(listOf(100L, 20L, 80L, 40L)))
    }

    @Test
    fun medianDoesNotDependOnInputOrder() {
        assertEquals(
            TcpLatencyProbe.median(listOf(9L, 1L, 5L)),
            TcpLatencyProbe.median(listOf(5L, 9L, 1L))
        )
    }

    @Test
    fun medianAveragesLargeValuesWithoutOverflow() {
        assertEquals(
            Long.MAX_VALUE - 1L,
            TcpLatencyProbe.median(listOf(Long.MAX_VALUE - 2L, Long.MAX_VALUE))
        )
    }

    @Test
    fun boundedProbeStopsWaitingAtTheGlobalDeadline() {
        val timedOut = AtomicBoolean(false)
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            TcpLatencyProbe.runBounded(40L, onTimeout = { timedOut.set(true) }) {
                Thread.sleep(5_000L)
                "too late"
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("Probe should return close to its 40 ms deadline, elapsed=${elapsedMs}ms", elapsedMs < 1_000L)
        assertTrue(timedOut.get())
    }

    @Test
    fun socketRegisteredAfterTimeoutIsClosedImmediately() {
        val activeSocket = AtomicReference<Socket?>(null)
        val timedOut = AtomicBoolean(true)
        val socket = object : Socket() {
            var wasClosed = false
            override fun close() {
                wasClosed = true
            }
        }

        TcpLatencyProbe.registerActiveSocket(activeSocket, timedOut, socket)

        assertTrue(socket.wasClosed)
        assertEquals(null, activeSocket.get())
    }


    @Test
    fun timeoutClosesSocketThatWasAlreadyRegistered() {
        val activeSocket = AtomicReference<Socket?>(null)
        val timedOut = AtomicBoolean(false)
        val socket = object : Socket() {
            var closeCount = 0
            override fun close() {
                closeCount++
            }
        }
        activeSocket.set(socket)

        TcpLatencyProbe.closeActiveSocketOnTimeout(activeSocket, timedOut)

        assertTrue(timedOut.get())
        assertEquals(1, socket.closeCount)
        assertTrue(activeSocket.get() == null)
    }

    @Test
    fun timeoutSocketCleanupIsSafeWhenRepeated() {
        val activeSocket = AtomicReference<Socket?>(null)
        val timedOut = AtomicBoolean(false)
        val socket = object : Socket() {
            var closeCount = 0
            override fun close() {
                closeCount++
            }
        }
        activeSocket.set(socket)

        TcpLatencyProbe.closeActiveSocketOnTimeout(activeSocket, timedOut)
        TcpLatencyProbe.closeActiveSocketOnTimeout(activeSocket, timedOut)

        assertEquals(1, socket.closeCount)
        assertTrue(activeSocket.get() == null)
    }

    @Test
    fun boundedProbeReturnsCompletedOperation() {
        assertEquals("ok", TcpLatencyProbe.runBounded(1_000L) { "ok" })
    }

    @Test
    fun boundedProbePropagatesOperationErrors() {
        assertThrows(IllegalArgumentException::class.java) {
            TcpLatencyProbe.runBounded<Int>(1_000L) { throw IllegalArgumentException("bad endpoint") }
        }
    }

    @Test
    fun socketFactoryFailureDoesNotPreventNextTcpAttempt() {
        var createCalls = 0
        val socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket {
                createCalls++
                if (createCalls == 1) throw IOException("temporary socket creation failure")
                return object : Socket() {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
                }
            }

            override fun createSocket(host: String, port: Int): Socket = error("unused")
            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("unused")
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = error("unused")
        }
        val activeSocket = AtomicReference<Socket?>(null)
        val timedOut = AtomicBoolean(false)
        val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        val failedSample = TcpLatencyProbe.connectSample(
            socketFactory, address, 443, 1_000, activeSocket, timedOut
        )
        val successfulSample = TcpLatencyProbe.connectSample(
            socketFactory, address, 443, 1_000, activeSocket, timedOut
        )

        assertEquals(null, failedSample)
        assertTrue("The next TCP attempt should succeed", successfulSample != null)
        assertEquals(2, createCalls)
        assertEquals(null, activeSocket.get())
    }

    @Test
    fun medianRejectsAnEmptyMeasurementSet() {
        assertThrows(IllegalArgumentException::class.java) {
            TcpLatencyProbe.median(emptyList())
        }
    }
}
