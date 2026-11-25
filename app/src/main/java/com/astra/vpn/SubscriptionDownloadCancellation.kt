package com.astra.vpn

import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

/**
 * Cancellation bridge between WorkManager's stopping thread and a blocking
 * HTTPS read. Disconnecting the registered connection unblocks socket I/O;
 * late registrations are rejected after cancellation.
 */
class SubscriptionDownloadCancellation {
    private val lock = Any()
    private var cancelled = false
    private var activeConnection: HttpURLConnection? = null

    val isCancelled: Boolean
        get() = synchronized(lock) { cancelled }

    fun register(connection: HttpURLConnection): Boolean = synchronized(lock) {
        if (cancelled) return@synchronized false
        activeConnection = connection
        true
    }

    fun unregister(connection: HttpURLConnection) = synchronized(lock) {
        if (activeConnection === connection) activeConnection = null
    }

    fun cancel() {
        val connection = synchronized(lock) {
            cancelled = true
            activeConnection.also { activeConnection = null }
        }
        // Disconnect outside the monitor: it may block briefly while closing the socket.
        connection?.disconnect()
    }

    /**
     * Linearizes the final profile write against cancel(): either the write
     * finishes before cancellation takes effect, or cancellation prevents it.
     */
    fun <T> commitIfActive(action: () -> T): T = synchronized(lock) {
        if (cancelled) throw CancellationException("Загрузка подписки отменена")
        action()
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("Загрузка подписки отменена")
    }
}
