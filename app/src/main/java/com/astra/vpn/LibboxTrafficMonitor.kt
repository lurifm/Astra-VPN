package com.astra.vpn

import android.util.Log
import android.os.SystemClock
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox

/**
 * Owns the local libbox connection-event subscription for the VPN service process.
 * All blocking connect/disconnect operations run away from the main thread.
 */
internal class LibboxTrafficMonitor(
    private val onTrafficChanged: (Map<String, LibboxTrafficAccumulator.Totals>) -> Unit = {}
) {
    private val lock = java.lang.Object()
    private val tracker = LibboxTrafficEventTracker()
    private var lastPublishedAt = 0L
    private var client: CommandClient? = null
    private var worker: Thread? = null
    private var stopped = true
    private var disconnected = false

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
            disconnected = false
            tracker.reset()
            lastPublishedAt = 0L
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandConnections)
            }
            val newClient = CommandClient(TrafficHandler(), options)
            client = newClient
            worker = Thread({ runConnectionLoop(newClient) }, "Astra-LibboxTraffic").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runConnectionLoop(activeClient: CommandClient) {
        var retryDelayMs = INITIAL_RETRY_DELAY_MS
        while (isActive(activeClient)) {
            try {
                // connect() establishes the command channel and starts stream readers.
                activeClient.connect()
                synchronized(lock) {
                    while (!stopped && client === activeClient && !disconnected) {
                        lock.wait()
                    }
                    if (stopped || client !== activeClient) return
                    disconnected = false
                }
                retryDelayMs = INITIAL_RETRY_DELAY_MS
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (error: Exception) {
                Log.w(TAG, "libbox traffic subscription failed (${error.javaClass.simpleName})")
            }

            if (!isActive(activeClient)) return
            try {
                Thread.sleep(retryDelayMs)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        }
    }

    private fun isActive(candidate: CommandClient): Boolean =
        synchronized(lock) { !stopped && client === candidate }

    fun stop() {
        val oldClient: CommandClient?
        val oldWorker: Thread?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            oldClient = client
            oldWorker = worker
            client = null
            worker = null
            disconnected = true
            tracker.reset()
            lastPublishedAt = 0L
            lock.notifyAll()
        }
        oldWorker?.interrupt()
        if (oldClient != null) {
            Thread({
                runCatching { oldClient.disconnect() }
                    .onFailure { Log.w(TAG, "libbox traffic client disconnect failed (${it.javaClass.simpleName})") }
            }, "Astra-LibboxTrafficClose").apply {
                isDaemon = true
                start()
            }
        }
    }

    private inner class TrafficHandler : CommandClientHandler {
        override fun connected() {
            synchronized(lock) {
                disconnected = false
                lock.notifyAll()
            }
        }

        override fun disconnected(message: String?) {
            synchronized(lock) {
                if (stopped) return
                disconnected = true
                lock.notifyAll()
            }
            if (!message.isNullOrBlank()) {
                Log.w(TAG, "libbox traffic event stream disconnected")
            }
        }

        override fun setDefaultLogLevel(level: Int) = Unit
        override fun clearLogs() = Unit
        override fun writeLogs(messageList: io.nekohasekai.libbox.LogIterator?) = Unit
        override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage) = Unit
        override fun writeGroups(message: io.nekohasekai.libbox.OutboundGroupIterator?) = Unit
        override fun writeOutbounds(message: io.nekohasekai.libbox.OutboundGroupItemIterator?) = Unit
        override fun initializeClashMode(modeList: io.nekohasekai.libbox.StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            if (events == null) return
            val snapshot = synchronized(lock) {
                if (stopped) return
                val wasReset = events.reset
                if (wasReset) tracker.reset()
                val iterator = events.iterator()
                while (iterator.hasNext()) {
                    val event = iterator.next() ?: continue
                    val connection = event.connection
                    when (event.type) {
                        Libbox.ConnectionEventNew -> tracker.onNew(event.id, connection?.outbound)
                        Libbox.ConnectionEventUpdate -> tracker.onUpdate(
                            event.id,
                            connection?.outbound,
                            event.uplinkDelta,
                            event.downlinkDelta
                        )
                        Libbox.ConnectionEventClosed -> tracker.onClosed(event.id)
                    }
                }
                val now = SystemClock.elapsedRealtime()
                val shouldPublish = wasReset || lastPublishedAt == 0L || now - lastPublishedAt >= PUBLISH_INTERVAL_MS
                if (shouldPublish) lastPublishedAt = now
                if (shouldPublish) tracker.snapshot() else null
            }
            if (snapshot != null) {
                runCatching { onTrafficChanged(snapshot) }
                    .onFailure { Log.w(TAG, "traffic observer callback failed (${it.javaClass.simpleName})") }
            }
        }
    }

    companion object {
        private const val TAG = "LibboxTrafficMonitor"
        private const val PUBLISH_INTERVAL_MS = 1_000L
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
    }
}
