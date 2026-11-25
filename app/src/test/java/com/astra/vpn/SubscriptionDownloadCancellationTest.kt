package com.astra.vpn

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDownloadCancellationTest {
    private class TrackingConnection : HttpURLConnection(URL("https://example.com")) {
        var wasDisconnected = false

        override fun disconnect() {
            wasDisconnected = true
        }

        override fun connect() = Unit

        override fun usingProxy(): Boolean = false
    }

    @Test
    fun cancelDisconnectsRegisteredConnectionAndRejectsLateRegistration() {
        val cancellation = SubscriptionDownloadCancellation()
        val active = TrackingConnection()
        val late = TrackingConnection()

        assertTrue(cancellation.register(active))
        cancellation.cancel()

        assertTrue(cancellation.isCancelled)
        assertTrue(active.wasDisconnected)
        assertFalse(cancellation.register(late))
        assertFalse(late.wasDisconnected)
    }

    @Test
    fun unregisterPreventsCancelFromDisconnectingCompletedConnection() {
        val cancellation = SubscriptionDownloadCancellation()
        val connection = TrackingConnection()

        assertTrue(cancellation.register(connection))
        cancellation.unregister(connection)
        cancellation.cancel()

        assertFalse(connection.wasDisconnected)
    }

    @Test
    fun commitRunsWhileActiveAndReturnsItsValue() {
        val cancellation = SubscriptionDownloadCancellation()

        val result = cancellation.commitIfActive { 42 }

        assertEquals(42, result)
    }

    @Test(expected = CancellationException::class)
    fun commitIsRejectedAfterCancellation() {
        val cancellation = SubscriptionDownloadCancellation()
        cancellation.cancel()

        cancellation.commitIfActive { "must not be committed" }
    }

    @Test(expected = CancellationException::class)
    fun throwIfCancelledRejectsCancelledOperation() {
        val cancellation = SubscriptionDownloadCancellation()
        cancellation.cancel()

        cancellation.throwIfCancelled()
    }
}
