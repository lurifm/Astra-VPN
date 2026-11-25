package com.astra.vpn

import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.SystemClock
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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Runs URLTest in an isolated process so it never reloads the active VPN tunnel. */
internal class AstraLatencyTestService : VpnService(), CommandServerHandler {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // Only one native URLTest may exist at a time; the engine is recycled on timeout.
    private val urlTestDispatcher = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    // Startup is asynchronous; server may still be null when a duplicate START arrives.
    private val startAccepted = AtomicBoolean(false)
    // Each CommandClient is bound to one engine generation. Replacing the client
    // after recycle prevents buffered callbacks from the previous subscription
    // from being mistaken for results produced by the new engine.
    private val connectedGeneration = AtomicLong(-1L)
    @Volatile private var server: CommandServer? = null
    @Volatile private var serviceConfig: String? = null
    @Volatile private var engineRecycleInProgress = false
    private val engineGeneration = AtomicLong(0L)
    private val recycleHandoverTags = linkedSetOf<String>()
    private var recycleWatchdog: Runnable? = null
    @Volatile private var client: CommandClient? = null
    // Updated from the foreground Activity after Wi-Fi/cellular handovers.
    @Volatile private var probePlatform: AstraPlatform? = null
    // Covers a handover delivered before the native platform adapter is ready.
    @Volatile private var pendingPreferredNetwork: Network? = null
    @Volatile private var probeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val callbackNetworks = java.util.concurrent.ConcurrentHashMap<Network, ProbeNetworkState>()
    // Coalesce rapid Wi-Fi/cellular callback bursts. While a handover is pending,
    // never dispatch a probe that could immediately be invalidated by a newer route.
    @Volatile private var handoverInvalidationPending = false
    @Volatile private var handoverTargetNetwork: Network? = null
    private val handoverGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var expectedTags: Set<String> = emptySet()
    private var sessionId: String? = null
    private var latestResults = LatencyResultAccumulator(emptySet())
    private val batchTimeout = Runnable { finishAndPublish() }
    // Main-handler-owned scheduler state. URLTest() only triggers an asynchronous
    // native probe; a slot remains occupied until a result or its deadline arrives.
    private val completedTags = linkedSetOf<String>()
    private val inFlightTags = linkedSetOf<String>()
    private val pendingTags = java.util.ArrayDeque<String>()
    private val timedOutTags = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val errorTags = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // A timed-out native URLTest is cancelled by closing its isolated engine.
    private val handoverRetryCounts = mutableMapOf<String, Int>()
    private val perServerTimeouts = linkedMapOf<String, Runnable>()
    // sing-box includes URLTestTime (Unix seconds) in every outbound snapshot.
    // Keep the dispatch wall-clock time to reject a late result from an older attempt.
    private val probeDispatchEpochSeconds = linkedMapOf<String, Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }
            ACTION_NETWORK_UPDATE -> {
                val incomingSession = intent.getStringExtra(EXTRA_SESSION)
                val network = intent.getParcelableExtra<Network>(EXTRA_NETWORK)
                if (!stopped.get() && incomingSession == sessionId && network != null) {
                    preferProbeNetwork(network)
                }
                return START_NOT_STICKY
            }
        }
        if (!startAccepted.compareAndSet(false, true)) return START_NOT_STICKY
        sessionId = intent?.getStringExtra(EXTRA_SESSION)

        val config = intent?.getStringExtra(EXTRA_CONFIG)
        val tagsJson = intent?.getStringExtra(EXTRA_TAGS)
        val probeNetwork = intent?.getParcelableExtra<Network>(EXTRA_NETWORK)
        pendingPreferredNetwork = probeNetwork
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
        latestResults = LatencyResultAccumulator(expectedTags)
        pendingTags.clear()
        pendingTags.addAll(expectedTags)
        serviceConfig = config

        registerProbeNetworkCallback()
        startForeground(
            NOTIFICATION_ID,
            AstraNotification.create(this, AstraVpnService.STATE_CONNECTING, "Проверка серверов")
        )
        // Bound native engine startup. Once connected, each individual server
        // receives its own independent ten-second URLTest timeout at dispatch.
        handler.postDelayed(batchTimeout, ENGINE_STARTUP_TIMEOUT_MS)
        worker.execute {
            var createdServer: CommandServer? = null
            var createdClient: CommandClient? = null
            try {
                if (stopped.get()) return@execute
                val platform = AstraPlatform(this, { _ ->
                    failIfActive("Проверка неожиданно запросила VPN-интерфейс")
                    false
                }, protectSockets = false, initialPreferredPhysicalNetwork = probeNetwork, manageVpnUnderlyingNetworks = false)
                probePlatform = platform
                pendingPreferredNetwork?.let(platform::updatePreferredPhysicalNetwork)
                if (stopped.get()) return@execute
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

                val options = CommandClientOptions().apply { addCommand(Libbox.CommandOutbounds) }
                val commandClient = CommandClient(resultHandlerForGeneration(engineGeneration.get()), options)
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

    private fun resultHandlerForGeneration(callbackGeneration: Long) = object : CommandClientHandler {
        override fun connected() {
            if (callbackGeneration != engineGeneration.get()) return
            connectedGeneration.set(callbackGeneration)
            handler.post {
                if (stopped.get() || callbackGeneration != engineGeneration.get() ||
                    engineRecycleInProgress
                ) return@post
                handler.removeCallbacks(batchTimeout)
                // URLTest() is asynchronous in libbox. Limit actual native probes
                // until their result arrives, rather than merely limiting RPC calls.
                dispatchNextUrlTests(client)
            }
        }

        override fun disconnected(message: String?) {
            // The old client is intentionally disconnected during engine recycle.
            // Its callback must not be treated as a failure of the replacement client.
            handler.post {
                if (!stopped.get() && callbackGeneration == engineGeneration.get() &&
                    !engineRecycleInProgress
                ) {
                    publishFailure("Канал проверки отключён")
                    finish()
                }
            }
        }

        override fun setDefaultLogLevel(level: Int) = Unit
        override fun clearLogs() = Unit
        override fun writeLogs(messageList: LogIterator) {
            // Consume the native iterator on its callback thread. Keep only
            // diagnostics useful for URLTest/DNS troubleshooting and redact
            // subscription-style URIs before they reach Android Logcat.
            while (messageList.hasNext()) {
                val entry = messageList.next() ?: continue
                val raw = entry.message ?: continue
                if (raw.isBlank()) continue
                val lower = raw.lowercase()
                if (listOf("dns", "urltest", "url-test", "resolve", "timeout",
                        "failed", "failure", "error", "network", "connect")
                        .none(lower::contains)) continue
                Log.d(TAG, DiagnosticSanitizer.sanitize(raw).take(1_200))
            }
        }
        override fun writeStatus(message: StatusMessage?) = Unit

        override fun writeGroups(message: OutboundGroupIterator) = Unit

        override fun writeOutbounds(message: OutboundGroupItemIterator) {
            // This handler is permanently bound to the generation that created
            // its CommandClient; a delayed callback cannot inherit a newer generation.
            val snapshot = linkedMapOf<String, Pair<Long, Long>>()
            while (message.hasNext()) {
                val item = message.next()
                if (item.tag !in expectedTags) continue
                val delay = item.urlTestDelay.toLong()
                val resultTime = item.urlTestTime.toLong()
                if (delay > 0L && resultTime > 0L) snapshot[item.tag] = delay to resultTime
            }
            if (snapshot.isNotEmpty()) {
                handler.post {
                    if (stopped.get() || !LatencyEngineRecyclePolicy.shouldAcceptResult(
                            recycling = engineRecycleInProgress,
                            expectedGeneration = callbackGeneration,
                            currentGeneration = engineGeneration.get()
                        )
                    ) return@post
                    // SubscribeOutbounds publishes a full state snapshot, not a
                    // per-probe event. Ignore cached/unsolicited values for servers
                    // that have not actually been dispatched in this batch.
                    val accepted = snapshot.filterKeys { tag ->
                        val fresh = LatencyProbeDispatchPolicy.isResultFresh(
                            dispatchedAtEpochSeconds = probeDispatchEpochSeconds[tag],
                            resultAtEpochSeconds = snapshot.getValue(tag).second
                        )
                        fresh && LatencyProbeDispatchPolicy.shouldAcceptOutboundUpdate(
                            // Once a handover starts, in-flight probes belong
                            // to the previous route even during the debounce window.
                            isInFlight = tag in inFlightTags,
                            alreadyCompleted = tag in completedTags,
                            previouslyTimedOut = tag in timedOutTags,
                            discardedByNetworkChange = false,
                            handoverPending = handoverInvalidationPending,
                            engineRecycleInProgress = engineRecycleInProgress
                        )
                    }.filterKeys(::completeMeasuredTag).mapValues { it.value.first }
                    if (accepted.isEmpty()) return@post
                    latestResults.merge(accepted)
                    publishResults(accepted, finished = false)
                    dispatchNextUrlTests(client)
                    finishIfAllTagsCompleted()
                }
            }
        }

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
    }

    private fun dispatchNextUrlTests(activeClient: CommandClient?) {
        if (!LatencyEngineRecyclePolicy.shouldDispatch(
                stopped = stopped.get(),
                recycling = engineRecycleInProgress,
                handoverPending = handoverInvalidationPending
            ) || activeClient == null || pendingPreferredNetwork == null
        ) return
        while (inFlightTags.size < URL_TEST_DISPATCH_CONCURRENCY && pendingTags.isNotEmpty()) {
            val tag = pendingTags.removeFirst()
            if (!LatencyProbeDispatchPolicy.shouldDispatch(
                    stopped = stopped.get(),
                    alreadyCompleted = tag in completedTags,
                    handoverPending = handoverInvalidationPending,
                    engineRecycleInProgress = engineRecycleInProgress
                ) || !inFlightTags.add(tag)
            ) continue

            val startedAt = SystemClock.uptimeMillis()
            probeDispatchEpochSeconds[tag] = System.currentTimeMillis() / 1_000L
            val deadline = Runnable { completeUnmeasuredTag(tag, timedOut = true) }
            perServerTimeouts[tag] = deadline
            handler.postAtTime(deadline, startedAt + SERVER_URLTEST_TIMEOUT_MS)

            try {
                urlTestDispatcher.execute {
                    if (stopped.get()) return@execute
                    runCatching { activeClient.urlTest(tag) }
                        .onFailure {
                            handler.post { completeUnmeasuredTag(tag, timedOut = false) }
                        }
                }
            } catch (_: RejectedExecutionException) {
                if (!stopped.get()) completeUnmeasuredTag(tag, timedOut = false)
            }
        }
    }

    private fun completeMeasuredTag(tag: String): Boolean {
        val newlyCompleted = completedTags.add(tag)
        if (!newlyCompleted) return false
        inFlightTags.remove(tag)
        // A successful measurement closes this retry episode. A later, unrelated
        // network handover must receive its own bounded retry budget.
        handoverRetryCounts.remove(tag)
        perServerTimeouts.remove(tag)?.let { handler.removeCallbacks(it) }
        probeDispatchEpochSeconds.remove(tag)
        return true
    }

    private fun completeUnmeasuredTag(tag: String, timedOut: Boolean) {
        if (stopped.get() || tag !in expectedTags || !completedTags.add(tag)) return
        perServerTimeouts.remove(tag)?.let { handler.removeCallbacks(it) }
        if (timedOut) {
            timedOutTags.add(tag)
            // The 10s deadline is a real native cancellation boundary:
            // closeService() cancels the box instance context used by URLTest.
            publishResults(mapOf(tag to null), finished = false)
            recycleLatencyEngine(requeueAfterHandover = emptyList())
            return
        }
        errorTags.add(tag)
        inFlightTags.remove(tag)
        probeDispatchEpochSeconds.remove(tag)
        publishResults(mapOf(tag to null), finished = false)
        dispatchNextUrlTests(client)
        finishIfAllTagsCompleted()
    }

    /**
     * Cancel the one active native URLTest by closing its owning sing-box instance,
     * then start a fresh instance on the same CommandServer. SubscribeOutbounds is
     * attached to the StartedService lifetime and survives service-instance reloads.
     * All lifecycle calls are serialized on the worker, never on the UI thread.
     */
    private fun recycleLatencyEngine(requeueAfterHandover: List<String>) {
        if (!LatencyEngineRecyclePolicy.shouldStartRecycle(stopped.get(), engineRecycleInProgress)) return
        val activeServer = server
        val config = serviceConfig
        if (activeServer == null || config.isNullOrBlank()) {
            failIfActive("Не удалось перезапустить движок проверки")
            return
        }

        engineRecycleInProgress = true
        val generation = engineGeneration.incrementAndGet()
        recycleHandoverTags.clear()
        recycleHandoverTags.addAll(requeueAfterHandover)
        recycleWatchdog?.let(handler::removeCallbacks)
        val watchdog = Runnable {
            if (!stopped.get() && engineGeneration.get() == generation) {
                publishFailure("Перезапуск движка проверки превысил допустимое время")
                finish()
            }
        }
        recycleWatchdog = watchdog
        handler.postDelayed(watchdog, LatencyEngineRecyclePolicy.RECYCLE_TIMEOUT_MS)

        executeWorkerIfActive {
            try {
                // Disconnect the old subscription before replacing the native
                // instance. Its handler retains the old generation and rejects any
                // callback that was already queued by libbox.
                val previousClient = client
                client = null
                previousClient?.let { oldClient ->
                    runCatching { oldClient.disconnect() }
                        .onFailure { Log.w(TAG, "latency client recycle disconnect failed (${it.javaClass.simpleName})") }
                }
                activeServer.closeService()
                if (stopped.get() || engineGeneration.get() != generation) return@executeWorkerIfActive
                activeServer.startOrReloadService(config, OverrideOptions())
                if (stopped.get() || engineGeneration.get() != generation) {
                    // STOP may race a blocking native start. Do not leave a fresh
                    // engine running after the service session has been cancelled.
                    runCatching { activeServer.closeService() }
                    return@executeWorkerIfActive
                }
                val options = CommandClientOptions().apply { addCommand(Libbox.CommandOutbounds) }
                val replacementClient = CommandClient(resultHandlerForGeneration(generation), options)
                client = replacementClient
                replacementClient.connect()
                handler.post {
                    if (stopped.get() || engineGeneration.get() != generation) return@post
                    recycleWatchdog?.let(handler::removeCallbacks)
                    recycleWatchdog = null
                    engineRecycleInProgress = false
                    inFlightTags.clear()
                    probeDispatchEpochSeconds.clear()
                    recycleHandoverTags.forEach { tag ->
                        val retries = handoverRetryCounts[tag] ?: 0
                        val hasValidatedUnderlay = pendingPreferredNetwork != null
                        if (LatencyEngineRecyclePolicy.shouldRequeueAfterHandover(retries, hasValidatedUnderlay)) {
                            if (hasValidatedUnderlay) handoverRetryCounts[tag] = retries + 1
                            completedTags.remove(tag)
                            if (tag !in pendingTags) pendingTags.addFirst(tag)
                        } else {
                            errorTags.add(tag)
                        }
                    }
                    recycleHandoverTags.clear()
                    // The replacement subscription may connect before or after this
                    // handler runs. Whichever arrives second starts the next probe.
                    if (connectedGeneration.get() == generation) dispatchNextUrlTests(client)
                    finishIfAllTagsCompleted()
                }
            } catch (failure: Exception) {
                handler.post {
                    if (!stopped.get() && engineGeneration.get() == generation) {
                        Log.e(TAG, "Не удалось перезапустить движок Ping (" + failure.javaClass.simpleName + ")")
                        publishFailure("Не удалось восстановить проверку серверов")
                        finish()
                    }
                }
            }
        }
    }

    private fun finishIfAllTagsCompleted() {
        if (!stopped.get() && LatencyProbeDispatchPolicy.shouldFinish(
                completedAll = completedTags.containsAll(expectedTags),
                engineRecycleInProgress = engineRecycleInProgress
            )) finishAndPublish()
    }

    private fun finishAndPublish() {
        if (stopped.get()) return
        val results = latestResults.snapshot()
        publishResults(results, finished = true)
        finish()
    }

    private fun publishResults(results: Map<String, Long?>, finished: Boolean) {
        val json = JSONObject()
        results.forEach { (tag, delay) -> json.put(tag, delay ?: JSONObject.NULL) }
        val payload = json.toString()
        // Only the terminal snapshot is persisted for Activity recreation.
        // Partial snapshots are live UI updates and must not replace the final result.
        if (finished && latestResults.hasAnyMeasurement()) {
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
                .putExtra(EXTRA_COMPLETED_TAGS, JSONArray(completedTags.toList().sorted()).toString())
                .putExtra(EXTRA_TIMEOUT_TAGS, JSONArray(timedOutTags.toList().sorted()).toString())
                .putExtra(EXTRA_ERROR_TAGS, JSONArray(errorTags.toList().sorted()).toString())
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
        if (latestResults.hasAnyMeasurement()) {
            val snapshot = latestResults.snapshot()
            val json = JSONObject()
            snapshot.forEach { (tag, delay) -> json.put(tag, delay ?: JSONObject.NULL) }
            getSharedPreferences(LATENCY_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_RESULTS, json.toString())
                .putString(KEY_LAST_RESULTS, json.toString())
                .remove(KEY_ACTIVE_SESSION_ID)
                .apply()
        } else {
            // An engine failure before the first measurement is not a result.
            // Keep the previous successful cache intact for Activity recreation.
            getSharedPreferences(LATENCY_PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_ACTIVE_SESSION_ID)
                .apply()
        }
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName)
            .putExtra(EXTRA_ERROR, message)
            .putExtra(EXTRA_SESSION, sessionId))
    }

    /**
     * Keep physical-network selection alive with the foreground probe service,
     * even when MainActivity is stopped or the app is backgrounded.
     */
    private fun registerProbeNetworkCallback() {
        if (probeNetworkCallback != null) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Android may deliver callbacks from a connectivity thread. Serialize
            // all callback state changes with the service's main-handler scheduler.
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                handler.post {
                    handleProbeNetworkCapabilities(connectivity, network, networkCapabilities)
                }
            }

            override fun onLost(network: Network) {
                handler.post { handleProbeNetworkLost(network) }
            }
        }
        try {
            connectivity.registerNetworkCallback(request, callback)
            probeNetworkCallback = callback
        } catch (failure: Exception) {
            SafeLog.w(TAG, "Не удалось включить мониторинг сети для Ping", failure)
        }
    }


    private fun handleProbeNetworkCapabilities(
        connectivity: ConnectivityManager,
        network: Network,
        networkCapabilities: NetworkCapabilities
    ) {
        if (stopped.get()) return
        val notSuspended = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        } else {
            true
        }
        val usable = NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(
            isVpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isNotSuspended = notSuspended
        )
        if (!usable) {
            callbackNetworks.remove(network)
            if (pendingPreferredNetwork == network) {
                val fallback = selectFallbackProbeNetwork(network)
                if (fallback == null) {
                    pendingPreferredNetwork = null
                    scheduleProbeNetworkHandoverInvalidation(null)
                } else {
                    preferProbeNetwork(fallback)
                }
            }
            return
        }
        val priority = when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }
        callbackNetworks[network] = ProbeNetworkState(priority)
        val preferred = pendingPreferredNetwork
        val currentPreferredIsUsable = preferred?.let { preferredNetwork ->
            callbackNetworks.containsKey(preferredNetwork) ||
                runCatching {
                    val caps = connectivity.getNetworkCapabilities(preferredNetwork)
                        ?: return@runCatching false
                    val preferredNotSuspended =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                        } else {
                            true
                        }
                    NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(
                        isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        isNotSuspended = preferredNotSuspended
                    )
                }.getOrDefault(false)
        } ?: false
        if (NetworkRecoveryPolicy.shouldAdoptProbeNetwork(
                currentPreferredIsUsable = currentPreferredIsUsable,
                callbackIsPreferred = preferred == network
            )
        ) {
            preferProbeNetwork(network)
        }
    }

    private fun handleProbeNetworkLost(network: Network) {
        if (stopped.get()) return
        callbackNetworks.remove(network)
        if (pendingPreferredNetwork == network) {
            val fallback = selectFallbackProbeNetwork(network)
            if (fallback == null) {
                pendingPreferredNetwork = null
                scheduleProbeNetworkHandoverInvalidation(null)
            } else {
                preferProbeNetwork(fallback)
            }
        } else {
            probePlatform?.clearPreferredPhysicalNetwork(network)
        }
    }

    private data class ProbeNetworkState(val transportPriority: Int)

    /**
     * Re-select through AstraPlatform's canonical ordering: validated physical
     * networks first, then the active/default route, then transport preference.
     * The callback map is only a startup fallback while the platform adapter is
     * not ready; choosing its highest transport alone can select a non-default
     * Wi-Fi network over the actual cellular underlay.
     */
    private fun selectFallbackProbeNetwork(lostNetwork: Network): Network? {
        val platform = probePlatform
        platform?.clearPreferredPhysicalNetwork(lostNetwork)
        val refreshed = platform?.refreshPreferredPhysicalNetwork()
        return NetworkRecoveryPolicy.chooseProbeFallback(
            platformReady = platform != null,
            refreshedCandidate = refreshed,
            callbackCandidate = callbackNetworks.entries
                .maxByOrNull { it.value.transportPriority }?.key
        )
    }

    private fun preferProbeNetwork(network: Network) {
        if (stopped.get()) return
        val previous = pendingPreferredNetwork
        pendingPreferredNetwork = network
        probePlatform?.updatePreferredPhysicalNetwork(network)
        if ((previous != null && previous != network) || handoverInvalidationPending) {
            scheduleProbeNetworkHandoverInvalidation(network)
        } else {
            // Also resumes tags queued while the device had no validated underlay.
            handler.post { dispatchNextUrlTests(client) }
        }
    }

    /**
     * Network callbacks can arrive in bursts (for example, Wi-Fi loss followed
     * by cellular validation). Only the newest route transition may invalidate
     * probes; otherwise an older queued callback can invalidate probes already
     * dispatched on the newest underlay.
     */
    private fun scheduleProbeNetworkHandoverInvalidation(target: Network?) {
        if (stopped.get()) return
        // Repeated capability/network-update callbacks for the same target must
        // not keep pushing the debounce deadline forward indefinitely.
        if (handoverInvalidationPending && handoverTargetNetwork == target) return
        handoverInvalidationPending = true
        handoverTargetNetwork = target
        val generation = handoverGeneration.incrementAndGet()
        handler.postDelayed({
            if (stopped.get() || !NetworkRecoveryPolicy.isLatestNetworkChange(
                    generation,
                    handoverGeneration.get()
                )
            ) return@postDelayed
            handoverInvalidationPending = false
            handoverTargetNetwork = null
            invalidateInFlightProbesForNetworkHandover()
            dispatchNextUrlTests(client)
        }, NetworkRecoveryPolicy.NETWORK_CHANGE_DEBOUNCE_MS)
    }

    /**
     * A probe opened on the previous Android Network cannot be attributed to the
     * new route. Recycle the isolated engine to cancel it, then retry within the
     * existing bounded handover retry budget.
     */
    private fun invalidateInFlightProbesForNetworkHandover() {
        if (stopped.get() || engineRecycleInProgress) return
        val affectedTags = inFlightTags.toList()
        if (affectedTags.isEmpty()) {
            dispatchNextUrlTests(client)
            return
        }
        affectedTags.forEach { tag ->
            perServerTimeouts.remove(tag)?.let(handler::removeCallbacks)
            completedTags.add(tag)
            publishResults(mapOf(tag to null), finished = false)
        }
        recycleLatencyEngine(requeueAfterHandover = affectedTags)
    }

    private fun unregisterProbeNetworkCallback() {
        val callback = probeNetworkCallback ?: return
        probeNetworkCallback = null
        callbackNetworks.clear()
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }.onFailure {
            SafeLog.w(TAG, "Не удалось остановить мониторинг сети Ping", it)
        }
    }

    private fun finish() {
        if (!stopped.compareAndSet(false, true)) return

        // A user may cancel after some servers have already returned valid Ping.
        // Persist only when this run has at least one real sample: this lets a
        // recreated Activity recover partial progress without replacing a good
        // previous cache with an all-unknown snapshot.
        val partialSnapshot = latestResults.snapshot()
        if (latestResults.hasAnyMeasurement()) {
            val partialJson = JSONObject()
            partialSnapshot.forEach { (tag, delay) -> partialJson.put(tag, delay ?: JSONObject.NULL) }
            val payload = partialJson.toString()
            getSharedPreferences(LATENCY_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_RESULTS, payload)
                .putString(KEY_LAST_RESULTS, payload)
                .apply()
        }

        handler.removeCallbacks(batchTimeout)
        perServerTimeouts.values.forEach { handler.removeCallbacks(it) }
        perServerTimeouts.clear()
        recycleWatchdog?.let(handler::removeCallbacks)
        recycleWatchdog = null
        engineRecycleInProgress = false
        engineGeneration.incrementAndGet()
        recycleHandoverTags.clear()
        inFlightTags.clear()
        pendingTags.clear()
        probeDispatchEpochSeconds.clear()
        // Interrupt queued dispatch tasks; native URLTest calls already in progress
        // may be non-interruptible, but they cannot publish after stopped becomes true.
        urlTestDispatcher.shutdownNow()
        // CommandClient.disconnect() and CommandServer.close*() cross the
        // native libbox boundary. Never run them on the main thread: STOP,
        // timeout and service callbacks must release the UI/service lifecycle
        // promptly even if native shutdown is slow.
        val currentClient = client
        client = null
        val currentServer = server
        server = null
        probePlatform = null
        pendingPreferredNetwork = null
        handoverInvalidationPending = false
        handoverTargetNetwork = null
        handoverGeneration.incrementAndGet()
        unregisterProbeNetworkCallback()
        closeLibboxResourcesOnWorker(currentClient, currentServer)
        getSharedPreferences(LATENCY_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE_SESSION_ID).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Keep every CommandServer lifecycle call on the same serial worker. This
     * prevents STOP cleanup from racing a timeout/handover recycle that is
     * currently inside closeService() or startOrReloadService().
     */
    private fun closeLibboxResourcesOnWorker(
        activeClient: CommandClient?,
        activeServer: CommandServer?
    ) {
        try {
            worker.execute {
                if (activeClient != null) {
                    runCatching { activeClient.disconnect() }
                        .onFailure { Log.w(TAG, "latency client shutdown failed (${it.javaClass.simpleName})") }
                }
                if (activeServer != null) {
                    // Plain-outbound URLTest is bound to the box instance context.
                    // CloseService cancels that context; disconnecting gRPC alone does not.
                    runCatching { activeServer.closeService() }
                        .onFailure { Log.w(TAG, "latency service shutdown failed (${it.javaClass.simpleName})") }
                    runCatching { activeServer.close() }
                        .onFailure { Log.w(TAG, "latency server close failed (${it.javaClass.simpleName})") }
                }
            }
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "latency worker rejected serialized native cleanup")
        } finally {
            // Already-queued worker callbacks are harmless: they check stopped
            // before touching native state. Let the cleanup task drain first.
            worker.shutdown()
        }
    }

    override fun serviceStop() { handler.post { finish() } }
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) {
        // Native debug messages use the same redaction as structured URLTest logs.
        if (!message.isNullOrBlank()) {
            Log.d(TAG, DiagnosticSanitizer.sanitize(message).take(1_500))
        }
    }

    override fun connectSSHAgent(): Int = -1

    override fun onDestroy() {
        finish()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.action.START_LATENCY_TEST"
        const val ACTION_STOP = "com.astra.vpn.action.STOP_LATENCY_TEST"
        const val ACTION_NETWORK_UPDATE = "com.astra.vpn.action.UPDATE_LATENCY_NETWORK"
        const val ACTION_RESULTS = "com.astra.vpn.action.LATENCY_RESULTS"
        const val ACTION_ERROR = "com.astra.vpn.action.LATENCY_ERROR"
        const val EXTRA_CONFIG = "com.astra.vpn.extra.LATENCY_CONFIG"
        const val EXTRA_TAGS = "com.astra.vpn.extra.LATENCY_TAGS"
        const val EXTRA_NETWORK = "com.astra.vpn.extra.LATENCY_NETWORK"
        const val EXTRA_RESULTS = "com.astra.vpn.extra.LATENCY_RESULTS"
        const val EXTRA_FINAL = "com.astra.vpn.extra.LATENCY_FINAL"
        const val EXTRA_COMPLETED_TAGS = "com.astra.vpn.extra.LATENCY_COMPLETED_TAGS"
        const val EXTRA_TIMEOUT_TAGS = "com.astra.vpn.extra.LATENCY_TIMEOUT_TAGS"
        const val EXTRA_ERROR_TAGS = "com.astra.vpn.extra.LATENCY_ERROR_TAGS"
        const val EXTRA_ERROR = "com.astra.vpn.extra.LATENCY_ERROR"
        const val EXTRA_SESSION = "com.astra.vpn.extra.LATENCY_SESSION"
        private const val LATENCY_PREFS = "astra_latency_results"
        private const val KEY_PENDING_RESULTS = "pending_results"
        private const val KEY_LAST_RESULTS = "last_results"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val TAG = "AstraLatencyTest"
        private const val NOTIFICATION_ID = 2
        private const val ENGINE_STARTUP_TIMEOUT_MS = 8_000L
        private const val SERVER_URLTEST_TIMEOUT_MS = LatencyEngineRecyclePolicy.SERVER_TIMEOUT_MS
        private const val URL_TEST_DISPATCH_CONCURRENCY = 1

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

        /** Update only the underlay preference; the native test engine and VPN stay running. */
        fun updatePhysicalNetwork(context: Context, network: Network, sessionId: String) {
            val intent = Intent(context, AstraLatencyTestService::class.java)
                .setAction(ACTION_NETWORK_UPDATE)
                .putExtra(EXTRA_NETWORK, network)
                .putExtra(EXTRA_SESSION, sessionId)
            context.startService(intent)
        }
    }
}