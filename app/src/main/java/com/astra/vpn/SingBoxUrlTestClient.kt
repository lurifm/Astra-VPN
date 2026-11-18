package com.astra.vpn

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads sing-box's native URLTest group results from the local libbox command
 * channel. The delay values originate from sing-box HTTP URL tests performed
 * through the group's individual proxy outbounds; this class never performs
 * TCP-connect timing or derives a value from the server port.
 *
 * The command server must already be running with an outbound group tagged
 * [SingBoxUrlTestConfig.GROUP_TAG]. Lifecycle ownership stays with the caller.
 */
internal class SingBoxUrlTestClient(
    private val expectedTags: Set<String>,
    private val onResults: (Map<String, Long?>) -> Unit,
    private val onDisconnected: (String) -> Unit = {}
) : Closeable {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val receivedTags = expectedTags.toSet()

    private val options = CommandClientOptions().apply {
        addCommand(Libbox.CommandGroup)
    }

    private val handler = object : CommandClientHandler {
        override fun connected() {
            // Do not issue URLTest from inside libbox's callback thread.
            worker.execute {
                if (!closed.get()) {
                    runCatching { client.urlTest(SingBoxUrlTestConfig.GROUP_TAG) }
                        .onFailure { onDisconnected("Не удалось запустить URL-test") }
                }
            }
        }

        override fun disconnected(message: String?) {
            if (!closed.get()) onDisconnected("Канал проверки sing-box отключён")
        }

        override fun setDefaultLogLevel(level: Int) = Unit
        override fun clearLogs() = Unit
        override fun writeLogs(messageList: LogIterator) = Unit
        override fun writeStatus(message: StatusMessage?) = Unit

        override fun writeGroups(message: OutboundGroupIterator) {
            val snapshot = linkedMapOf<String, Long?>()
            while (message.hasNext()) {
                val group = message.next()
                if (group.tag != SingBoxUrlTestConfig.GROUP_TAG) continue
                val items = group.getItems()
                while (items.hasNext()) {
                    val item = items.next()
                    if (item.tag !in receivedTags) continue
                    val delay = item.urlTestDelay.toLong()
                    // A missing/failed URL test is represented as null, never 0 ms.
                    snapshot[item.tag] = delay.takeIf { it > 0L }
                }
            }
            if (snapshot.isNotEmpty() && !closed.get()) {
                onResults(snapshot)
            }
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator) = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
    }

    private val client: CommandClient = CommandClient(handler, options)

    /** Connect to the already-running local CommandServer. */
    fun start() {
        if (closed.get()) return
        worker.execute {
            if (closed.get()) return@execute
            runCatching { client.connect() }
                .onFailure {
                    if (!closed.get()) onDisconnected("Не удалось подключиться к движку проверки")
                }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client.disconnect() }
        worker.shutdownNow()
    }
}
