package com.astra.vpn

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox

/**
 * Owns the local libbox connection-event subscription for the VPN service process.
 * Only Update deltas are accumulated; connection IDs retain outbound attribution
 * when later Update events omit their Connection object.
 *
 * These are outbound-attributed connection counters, not certified complete TUN
 * totals and must not be used to trigger speed-based failover.
 */
internal class LibboxTrafficMonitor(
    private val onTrafficChanged: (Map<String, LibboxTrafficAccumulator.Totals>) -> Unit = {}
) {
    private val lock = Any()
    private val tracker = LibboxTrafficEventTracker()
    private var client: CommandClient? = null
    private var stopped = true

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
            tracker.reset()
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandConnections)
            }
            val newClient = CommandClient(TrafficHandler(), options)
            client = newClient
            Thread({
                try {
                    newClient.connect()
                } catch (error: Exception) {
                    synchronized(lock) {
                        if (!stopped && client === newClient) {
                            Log.w(TAG, "libbox traffic event subscription failed (" + error.javaClass.simpleName + ")")
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
            tracker.reset()
        }
        if (oldClient != null) {
            Thread({
                runCatching { oldClient.disconnect() }
                    .onFailure { Log.w(TAG, "libbox traffic client disconnect failed (" + it.javaClass.simpleName + ")") }
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
        override fun writeStatus(message: io.nekohasekai.libbox.StatusMessage?) = Unit
        override fun writeGroups(message: io.nekohasekai.libbox.OutboundGroupIterator?) = Unit
        override fun writeOutbounds(message: io.nekohasekai.libbox.OutboundGroupItemIterator?) = Unit
        override fun initializeClashMode(modeList: io.nekohasekai.libbox.StringIterator?, currentMode: String?) = Unit
        override fun updateClashMode(newMode: String?) = Unit

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            if (events == null) return
            val snapshot = synchronized(lock) {
                if (stopped) return
                if (events.reset) tracker.reset()
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
                tracker.snapshot()
            }
            if (!isStopped()) onTrafficChanged(snapshot)
        }
    }

    private fun isStopped(): Boolean = synchronized(lock) { stopped }

    companion object {
        private const val TAG = "LibboxTrafficMonitor"
    }
}
