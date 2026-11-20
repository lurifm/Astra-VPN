package com.astra.vpn

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox

/**
 * Owns the local libbox connection-event subscription for the VPN service process.
 * Connect/disconnect are kept off the main thread because libbox performs socket I/O.
 */
internal class LibboxTrafficMonitor(
    private val onTrafficChanged: (Map<String, LibboxTrafficAccumulator.Totals>) -> Unit = {}
) {
    private val lock = Any()
    private val accumulator = LibboxTrafficAccumulator()
    private var client: CommandClient? = null
    private var worker: Thread? = null
    private var stopped = true

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
            accumulator.reset()
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandConnections)
            }
            val handler = TrafficHandler()
            val newClient = CommandClient(handler, options)
            client = newClient
            worker = Thread({
                try {
                    newClient.connect()
                } catch (error: Exception) {
                    synchronized(lock) {
                        if (!stopped && client === newClient) {
                            Log.w(TAG, "libbox traffic event subscription failed (${error.javaClass.simpleName})")
                        }
                    }
                }
            }, "Astra-LibboxTraffic").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        val oldClient: CommandClient?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            oldClient = client
            client = null
            worker = null
            accumulator.reset()
        }
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
        override fun connected() = Unit
        override fun disconnected(message: String?) {
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
            synchronized(lock) {
                if (stopped) return
                if (events.reset) accumulator.reset()
                val iterator = events.iterator()
                while (iterator.hasNext()) {
                    val event = iterator.next() ?: continue
                    // Update events carry deltas. New/Closed snapshots may contain
                    // cumulative totals and must not be added a second time.
                    if (event.type != 1) continue // ConnectionEventUpdate
                    val outbound = event.connection?.outbound
                    accumulator.add(outbound, event.uplinkDelta, event.downlinkDelta)
                }
                onTrafficChanged(accumulator.snapshot())
            }
        }
    }

    companion object {
        private const val TAG = "LibboxTrafficMonitor"
    }
}
