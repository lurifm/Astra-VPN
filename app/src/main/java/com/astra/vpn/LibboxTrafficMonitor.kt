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
    // Invalidates callbacks from a previous client after stop/restart.
    private var generation = 0L

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
            disconnected = false
            generation += 1L
            val activeGeneration = generation
            tracker.reset()
            lastPublishedAt = 0L
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandConnections)
            }
            val newClient = CommandClient(TrafficHandler(activeGeneration), options)
            client = newClient
            worker = Thread({ runConnectionLoop(newClient, activeGeneration) }, "Astra-LibboxTraffic").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runConnectionLoop(activeClient: CommandClient, activeGeneration: Long) {
        val retryPolicy = TrafficMonitorRetryPolicy()
        while (isActive(activeClient, activeGeneration)) {
            var connectedAt = 0L
            try {
                // connect() establishes the command channel and starts stream readers.
                activeClient.connect()
                connectedAt = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    while (!stopped && generation == activeGeneration && client === activeClient && !disconnected) {
                        lock.wait()
                    }
                    if (stopped || generation != activeGeneration || client !== activeClient) return
                    disconnected = false
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (error: Exception) {
                Log.w(TAG, "libbox traffic subscription failed (${error.javaClass.simpleName})")
            }

            if (!isActive(activeClient, activeGeneration)) return
            val connectedDuration = if (connectedAt > 0L) {
                (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L)
            } else 0L
            val delayMs = retryPolicy.nextDelayAfter(connectedDuration)
            try {
                Thread.sleep(delayMs)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun isActive(candidate: CommandClient, candidateGeneration: Long): Boolean =
        synchronized(lock) { !stopped && generation == candidateGeneration && client === candidate }

    fun stop() {
        val oldClient: CommandClient?
        val oldWorker: Thread?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            generation += 1L
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

    private inner class TrafficHandler(private val ownerGeneration: Long) : CommandClientHandler {
        override fun connected() {
            synchronized(lock) {
                if (stopped || generation != ownerGeneration) return
                disconnected = false
                lock.notifyAll()
            }
        }

        override fun disconnected(message: String?) {
            synchronized(lock) {
                if (stopped || generation != ownerGeneration) return
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
                if (stopped || generation != ownerGeneration) return
                val wasReset = events.reset
                if (wasReset) tracker.reset()
                val iterator = events.iterator()
                while (iterator.hasNext()) {
                    val event = iterator.next() ?: continue
                    val connection = event.connection
                    when (event.type) {
                        Libbox.ConnectionEventNew.toInt() -> tracker.onNew(event.id, connection?.outbound)
                        Libbox.ConnectionEventUpdate.toInt() -> tracker.onUpdate(
                            event.id,
                            connection?.outbound,
                            event.uplinkDelta,
                            event.downlinkDelta
                        )
                        Libbox.ConnectionEventClosed.toInt() -> tracker.onClosed(event.id)
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
    }
}
