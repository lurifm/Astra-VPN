package com.astra.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Runs URLTest in an isolated process so it never reloads the active VPN tunnel. */
internal class AstraLatencyTestService : VpnService(), CommandServerHandler {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    private var server: CommandServer? = null
    private var client: CommandClient? = null
    private var expectedTags: Set<String> = emptySet()
    private val latestResults = linkedMapOf<String, Long?>()
    private val timeout = Runnable { finishAndPublish() }
    private val completionDebounce = Runnable { finishAndPublish() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (server != null) return START_NOT_STICKY

        val config = intent?.getStringExtra(EXTRA_CONFIG)
        val tagsJson = intent?.getStringExtra(EXTRA_TAGS)
        if (config.isNullOrBlank() || tagsJson.isNullOrBlank()) {
            publishFailure("Не передана конфигурация проверки")
            finish()
            return START_NOT_STICKY
        }
        expectedTags = runCatching {
            val array = JSONArray(tagsJson)
            buildSet { for (index in 0 until array.length()) add(array.getString(index)) }
        }.getOrDefault(emptySet())
        if (expectedTags.isEmpty()) {
            publishFailure("Нет серверов для проверки")
            finish()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            AstraNotification.create(this, AstraVpnService.STATE_CONNECTING, "Проверка серверов")
        )
        try {
            val platform = AstraPlatform(this) { descriptor ->
                runCatching { descriptor.close() }
                publishFailure("Проверка неожиданно запросила VPN-интерфейс")
                handler.post { finish() }
            }
            val commandServer = CommandServer(this, platform)
            server = commandServer
            commandServer.start()
            commandServer.startOrReloadService(config, OverrideOptions())

            val options = CommandClientOptions().apply { addCommand(Libbox.CommandGroup) }
            val commandClient = CommandClient(resultHandler, options)
            client = commandClient
            commandClient.connect()
            handler.postDelayed(timeout, TEST_TIMEOUT_MS)
        } catch (failure: Exception) {
            Log.e(TAG, "Не удалось запустить изолированную проверку (" + failure.javaClass.simpleName + ")")
            publishFailure("Не удалось запустить проверку серверов")
            finish()
        }
        return START_NOT_STICKY
    }

    private val resultHandler = object : CommandClientHandler {
        override fun connected() {
            worker.execute {
                runCatching { client?.urlTest(SingBoxUrlTestConfig.GROUP_TAG) }
                    .onFailure {
                        publishFailure("Не удалось запустить URL-test")
                        handler.post { finish() }
                    }
            }
        }

        override fun disconnected(message: String?) {
            if (!stopped.get()) {
                publishFailure("Канал проверки отключён")
                handler.post { finish() }
            }
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
                    if (item.tag !in expectedTags) continue
                    snapshot[item.tag] = item.urlTestDelay.toLong().takeIf { it > 0L }
                }
            }
            if (snapshot.isNotEmpty() && !stopped.get()) {
                synchronized(latestResults) {
                    latestResults.putAll(snapshot)
                    if (latestResults.keys.containsAll(expectedTags)) {
                        handler.removeCallbacks(completionDebounce)
                        handler.postDelayed(completionDebounce, RESULT_SETTLE_MS)
                    }
                }
            }
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator) = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
    }

    private fun finishAndPublish() {
        if (stopped.get()) return
        val results = synchronized(latestResults) { expectedTags.associateWith { latestResults[it] } }
        publishResults(results)
        finish()
    }

    private fun publishResults(results: Map<String, Long?>) {
        val json = JSONObject()
        results.forEach { (tag, delay) -> json.put(tag, delay ?: JSONObject.NULL) }
        sendBroadcast(Intent(ACTION_RESULTS).setPackage(packageName).putExtra(EXTRA_RESULTS, json.toString()))
    }

    private fun publishFailure(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_ERROR, message))
    }

    private fun finish() {
        if (!stopped.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(completionDebounce)
        runCatching { client?.disconnect() }
        client = null
        val currentServer = server
        server = null
        if (currentServer != null) {
            runCatching { currentServer.closeService() }
            runCatching { currentServer.close() }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun serviceStop() { handler.post { finish() } }
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) = Unit
    override fun connectSSHAgent(): Int = -1

    override fun onDestroy() {
        finish()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.action.START_LATENCY_TEST"
        const val ACTION_STOP = "com.astra.vpn.action.STOP_LATENCY_TEST"
        const val ACTION_RESULTS = "com.astra.vpn.action.LATENCY_RESULTS"
        const val ACTION_ERROR = "com.astra.vpn.action.LATENCY_ERROR"
        const val EXTRA_CONFIG = "com.astra.vpn.extra.LATENCY_CONFIG"
        const val EXTRA_TAGS = "com.astra.vpn.extra.LATENCY_TAGS"
        const val EXTRA_RESULTS = "com.astra.vpn.extra.LATENCY_RESULTS"
        const val EXTRA_ERROR = "com.astra.vpn.extra.LATENCY_ERROR"
        private const val TAG = "AstraLatencyTest"
        private const val NOTIFICATION_ID = 2
        private const val TEST_TIMEOUT_MS = 20_000L
        private const val RESULT_SETTLE_MS = 2_000L

        fun start(context: Context, config: String, tags: Set<String>) {
            val intent = Intent(context, AstraLatencyTestService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_TAGS, JSONArray(tags.toList()).toString())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
