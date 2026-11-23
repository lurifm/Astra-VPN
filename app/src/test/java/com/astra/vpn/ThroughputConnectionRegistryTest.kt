package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class ThroughputConnectionRegistryTest {
    @Test
    fun connectionOpenedAfterCloseIsImmediatelyDisconnected() {
        val registry = ThroughputConnectionRegistry()
        val connection = FakeConnection()
        assertNull(registry.close())
        assertFalse(registry.register(connection))
        assertTrue(connection.disconnected.get())
    }

    @Test
    fun clearingOldConnectionDoesNotClearReplacement() {
        val registry = ThroughputConnectionRegistry()
        val first = FakeConnection()
        val second = FakeConnection()
        assertTrue(registry.register(first))
        assertTrue(registry.register(second))
        registry.clear(first)
        assertSame(second, registry.close())
    }

    @Test
    fun concurrentRegistrationAndCloseCannotLeakConnection() {
        repeat(300) {
            val registry = ThroughputConnectionRegistry()
            val connection = FakeConnection()
            val ready = CountDownLatch(1)
            val start = CountDownLatch(1)
            val registration = Thread {
                ready.countDown()
                start.await()
                registry.register(connection)
            }
            registration.start()
            ready.await()
            start.countDown()
            registry.close()
            registration.join()
            assertTrue(connection.disconnected.get() || registry.close() === connection)
            // If close won, register must have disconnected it; if register
            // won, close returned ownership and the caller must disconnect it.
            registry.close()?.disconnect()
            assertTrue(connection.disconnected.get())
        }
    }

    private class FakeConnection : HttpURLConnection(URL("http://localhost")) {
        val disconnected = AtomicBoolean(false)
        override fun connect() = Unit
        override fun disconnect() { disconnected.set(true) }
        override fun usingProxy() = false
    }
}
