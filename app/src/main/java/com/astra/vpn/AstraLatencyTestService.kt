package com.astra.vpn

import android.content.Context
import android.content.Intent
import android.net.Network
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
    // Startup is asynchronous; server may still be null when a duplicate START arrives.
    private val startAccepted = AtomicBoolean(false)
    @Volatile private var server: CommandServer? = null
    @Volatile private var client: CommandClient? = null
    private var expectedTags: Set<String> = emptySet()
    private var sessionId: String? = null
    private val latestResults = linkedMapOf<String, Long?>()
    private val timeout = Runnable { finishAndPublish() }
    private val completionDebounce = Runnable { finishAndPublish() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (!startAccepted.compareAndSet(false, true)) return START_NOT_STICKY
        sessionId = intent?.getStringExtra(EXTRA_SESSION)

        val config = intent?.getStringExtra(EXTRA_CONFIG)
        val tagsJson = intent?.getStringExtra(EXTRA_TAGS)
        val probeNetwork = intent?.getParcelableExtra<Network>(EXTRA_NETWORK)
        if (config.isNullOrBlank() || tagsJson.isNullOrBlank() || probeNetwork == null) {
            publishFailure("Не передана конфигурация или физическая сеть для проверки")
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
        // Start the hard deadline immediately, then perform all libbox startup work
        // away from the main thread. Native engine startup can take noticeable time.
        handler.postDelayed(timeout, TEST_TIMEOUT_MS)
        worker.execute {
            var createdServer: CommandServer? = null
            var createdClient: CommandClient? = null
            try {
                if (stopped.get()) return@execute
                val platform = AstraPlatform(this, { descriptor ->
                    runCatching { descriptor.close() }
                    failIfActive("Проверка неожиданно запросила VPN-интерфейс")
                }, protectSockets = false, preferredPhysicalNetwork = probeNetwork, manageVpnUnderlyingNetworks = false)
                val commandServer = CommandServer(this, platform)
                createdServer = commandServer
                commandServer.start()
                if (stopped.get()) {
                    runCatching { commandServer.closeService() }
                    runCatching { commandServer.close() }
                    return@execute
                }
                commandServer.startOrReloadService(config, OverrideOptions())
                if (stopped.get()) {
                    runCatching { commandServer.closeService() }
                    runCatching { commandServer.close() }
                    return@execute
                }
                server = commandServer

                val options = CommandClientOptions().apply { addCommand(Libbox.CommandGroup) }
                val commandClient = CommandClient(resultHandler, options)
                createdClient = commandClient
                client = commandClient
                if (stopped.get()) {
                    runCatching { commandClient.disconnect() }
                    runCatching { commandServer.closeService() }
                    runCatching { commandServer.close() }
                    return@execute
                }
                commandClient.connect()
                if (stopped.get()) {
                    runCatching { commandClient.disconnect() }
                    runCatching { commandServer.closeService() }
                    runCatching { commandServer.close() }
                }
            } catch (failure: Exception) {
                Log.e(TAG, "Не удалось запустить изолированную проверку (" + failure.javaClass.simpleName + ")")
                if (stopped.get()) {
                    runCatching { createdClient?.disconnect() }
                    runCatching { createdServer?.closeService() }
                    runCatching { createdServer?.close() }
                } else {
                    failIfActive("Не удалось запустить проверку серверов")
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Native callbacks can arrive while finish()/onDestroy() is shutting the worker down.
     * Treat that race as a normal late callback instead of leaking RejectedExecutionException
     * back through the libbox callback thread.
     */
    private fun executeWorkerIfActive(task: () -> Unit) {
        if (stopped.get()) return
        try {
            worker.execute {
                if (!stopped.get()) task()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (!stopped.get()) {
                Log.w(TAG, "latency worker rejected an active callback")
            }
        }
    }

    private val resultHandler = object : CommandClientHandler {
        override fun connected() {
            executeWorkerIfActive {
                val activeClient = client ?: return@executeWorkerIfActive
                runCatching { activeClient.urlTest(SingBoxUrlTestConfig.GROUP_TAG) }
                    .onFailure {
                        if (!stopped.get()) {
                            failIfActive("Не удалось запустить URL-test")
                        }
                    }
            }
        }

        override fun disconnected(message: String?) {
            // Serialize terminal callback handling with timeout/completion on the main
            // handler. A callback that was already in flight must not publish an error
            // after finish() has committed the final result.
            handler.post {
                if (!stopped.get()) {
                    publishFailure("Канал проверки отключён")
                    finish()
                }
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
            if (snapshot.isNotEmpty()) {
                // Consume native iterators on the callback thread, but serialize all
                // state mutation and broadcasts with timeout/finish on the main handler.
                handler.post {
                    if (stopped.get()) return@post
                    val partial = synchronized(latestResults) {
                        snapshot.forEach { (tag, delay) ->
                            if (delay != null && delay > 0L) latestResults[tag] = delay
                        }
                        latestResults.toMap()
                    }
                    // Stream every successful measurement to the UI immediately.
                    // A slow/unavailable node no longer hides the results of faster nodes.
                    publishResults(partial, finished = false)
                    val allMeasured = synchronized(latestResults) {
                        latestResults.keys.containsAll(expectedTags) &&
                            latestResults.values.all { delay -> delay != null && delay > 0L }
                    }
                    // Zero values are sing-box's initial placeholders, not measurements.
                    // Finish early only when every outbound has a real positive result.
                    if (allMeasured) {
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
        publishResults(results, finished = true)
        finish()
    }

    private fun publishResults(results: Map<String, Long?>, finished: Boolean) {
        val json = JSONObject()
        results.forEach { (tag, delay) -> json.put(tag, delay ?: JSONObject.NULL) }
        val payload = json.toString()
        // Only the terminal snapshot is persisted for Activity recreation.
        // Partial snapshots are live UI updates and must not replace the final result.
        if (finished) {
            // Keep the latest completed measurements between app launches.
            // The pending copy is one-shot recovery for Activity recreation;
            // the last-results copy is a reusable display cache.
            getSharedPreferences(LATENCY_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_RESULTS, payload)
                .putString(KEY_LAST_RESULTS, payload)
                .apply()
        }
        sendBroadcast(
            Intent(ACTION_RESULTS)
                .setPackage(packageName)
                .putExtra(EXTRA_RESULTS, payload)
                .putExtra(EXTRA_FINAL, finished)
                .putExtra(EXTRA_SESSION, sessionId)
        )
    }

    /**
     * Serialize terminal failures with timeout and successful completion.
     * Rechecking stopped on the main handler prevents a late worker/native
     * failure from broadcasting ACTION_ERROR after a final result was committed.
     */
    private fun failIfActive(message: String) {
        handler.post {
            if (stopped.get()) return@post
            publishFailure(message)
            finish()
        }
    }

    private fun publishFailure(message: String) {
        // Preserve partial measurements if the Activity was recreated and misses
        // this terminal error broadcast. Never turn an engine failure into
        // evidence that every proxy is offline.
        if (expectedTags.isNotEmpty()) {
            val snapshot = synchronized(latestResults) {
                expectedTags.associateWith { latestResults[it] }
            }
            val json = JSONObject()
            snapshot.forEach { (tag, delay) -> json.put(tag, delay ?: JSONObject.NULL) }
            getSharedPreferences(LATENCY_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_RESULTS, json.toString())
                .putString(KEY_LAST_RESULTS, json.toString())
                .remove(KEY_ACTIVE_SESSION_ID)
                .apply()
        }
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName)
            .putExtra(EXTRA_ERROR, message)
            .putExtra(EXTRA_SESSION, sessionId))
    }

    private fun finish() {
        if (!stopped.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(completionDebounce)
        // CommandClient.disconnect() and CommandServer.close*() cross the
        // native libbox boundary. Never run them on the main thread: STOP,
        // timeout and service callbacks must release the UI/service lifecycle
        // promptly even if native shutdown is slow.
        val currentClient = client
        client = null
        val currentServer = server
        server = null
        closeLibboxResourcesAsync(currentClient, currentServer)
        getSharedPreferences(LATENCY_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE_SESSION_ID).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeLibboxResourcesAsync(
        activeClient: CommandClient?,
        activeServer: CommandServer?
    ) {
        if (activeClient == null && activeServer == null) return
        Thread({
            if (activeClient != null) {
                runCatching { activeClient.disconnect() }
                    .onFailure { Log.w(TAG, "latency client shutdown failed (${it.javaClass.simpleName})") }
            }
            if (activeServer != null) {
                runCatching { activeServer.closeService() }
                    .onFailure { Log.w(TAG, "latency service shutdown failed (${it.javaClass.simpleName})") }
                runCatching { activeServer.close() }
                    .onFailure { Log.w(TAG, "latency server close failed (${it.javaClass.simpleName})") }
            }
        }, "Astra-LatencyCleanup").apply {
            isDaemon = true
            start()
        }
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
        const val EXTRA_NETWORK = "com.astra.vpn.extra.LATENCY_NETWORK"
        const val EXTRA_RESULTS = "com.astra.vpn.extra.LATENCY_RESULTS"
        const val EXTRA_FINAL = "com.astra.vpn.extra.LATENCY_FINAL"
        const val EXTRA_ERROR = "com.astra.vpn.extra.LATENCY_ERROR"
        const val EXTRA_SESSION = "com.astra.vpn.extra.LATENCY_SESSION"
        private const val LATENCY_PREFS = "astra_latency_results"
        private const val KEY_PENDING_RESULTS = "pending_results"
        private const val KEY_LAST_RESULTS = "last_results"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val TAG = "AstraLatencyTest"
        private const val NOTIFICATION_ID = 2
        // Hard upper bound for a full server batch; unfinished nodes remain "no data".
        private const val TEST_TIMEOUT_MS = 10_000L
        private const val RESULT_SETTLE_MS = 2_000L

        fun start(context: Context, config: String, tags: Set<String>, network: Network, sessionId: String) {
            val intent = Intent(context, AstraLatencyTestService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_TAGS, JSONArray(tags.toList()).toString())
                .putExtra(EXTRA_NETWORK, network)
                .putExtra(EXTRA_SESSION, sessionId)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
