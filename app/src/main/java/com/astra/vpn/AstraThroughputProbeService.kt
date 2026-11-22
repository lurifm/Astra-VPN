package com.astra.vpn

import android.content.Intent
import android.net.Network
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one bounded throughput measurement through one imported proxy.
 * It lives in its own process and never establishes a TUN interface.
 */
internal class AstraThroughputProbeService : VpnService(), CommandServerHandler {
    private val stopped = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { fail("Проверка скорости превысила лимит времени") }
    private val started = AtomicBoolean(false)
    private val measurementStarted = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val probeWorker = Executors.newSingleThreadExecutor()
    @Volatile private var commandServer: CommandServer? = null
    @Volatile private var commandClient: CommandClient? = null
    @Volatile private var physicalNetwork: Network? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var profileId: String? = null
    @Volatile private var listenPort: Int = 0
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var measurementTimeoutMs: Long = TOTAL_TIMEOUT_MS
    @Volatile private var downloadByteLimit: Long = DOWNLOAD_BYTES
    @Volatile private var uploadByteLimit: Long = UPLOAD_BYTES

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A delayed STOP from an older batch item must not terminate a
            // newer probe running in this service instance.
            val requestedSession = intent.getStringExtra(EXTRA_SESSION)
            if (ThroughputSessionPolicy.shouldStop(sessionId, requestedSession)) {
                finish()
            } else {
                Log.w(TAG, "Ignoring stale or unscoped throughput stop request")
            }
            return START_NOT_STICKY
        }
        if (!started.compareAndSet(false, true)) return START_NOT_STICKY
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        physicalNetwork = intent?.getParcelableExtra(EXTRA_NETWORK)
        sessionId = intent?.getStringExtra(EXTRA_SESSION)
        profileId = intent?.getStringExtra(EXTRA_PROFILE)
        listenPort = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        if (intent?.getBooleanExtra(EXTRA_FAST_MODE, false) == true) {
            measurementTimeoutMs = FAST_TIMEOUT_MS
            downloadByteLimit = FAST_DOWNLOAD_BYTES
            uploadByteLimit = FAST_UPLOAD_BYTES
        }
        if (config.isNullOrBlank() || physicalNetwork == null ||
            profileId.isNullOrBlank() || listenPort !in 1..65535
        ) {
            publishError("Недостаточно данных для проверки скорости")
            finish()
            return START_NOT_STICKY
        }

        // The probe is pinned to the supplied physical Network. If that
        // network disappears, fail this sample promptly instead of waiting
        // for socket timeouts against a dead route.
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val expectedNetwork = physicalNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == expectedNetwork && !stopped.get()) {
                    fail("Мобильная сеть потеряна во время проверки скорости")
                }
            }
        }
        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
        } catch (error: RuntimeException) {
            networkCallback = null
            Log.e(TAG, "Unable to monitor the physical network (" + error.javaClass.simpleName + ")")
            publishError("Не удалось отслеживать состояние мобильной сети")
            finish()
            return START_NOT_STICKY
        }

        mainHandler.postDelayed(timeoutRunnable, measurementTimeoutMs)
        startForeground(
            NOTIFICATION_ID,
            AstraNotification.create(this, AstraVpnService.STATE_CONNECTING, "Измерение скорости сервера")
        )
        worker.execute {
            var localServer: CommandServer? = null
            var localClient: CommandClient? = null
            try {
                if (stopped.get()) return@execute
                val network = physicalNetwork ?: return@execute
                val platform = AstraPlatform(
                    this,
                    { _ -> false },
                    protectSockets = false,
                    initialPreferredPhysicalNetwork = network,
                    manageVpnUnderlyingNetworks = false
                )
                val server = CommandServer(this, platform)
                localServer = server
                commandServer = server
                server.start()
                if (stopped.get()) {
                    runCatching { server.closeService() }
                    runCatching { server.close() }
                    return@execute
                }
                server.startOrReloadService(config, OverrideOptions())
                if (stopped.get()) {
                    runCatching { server.closeService() }
                    runCatching { server.close() }
                    return@execute
                }
                commandServer = server

                val options = CommandClientOptions().apply { addCommand(Libbox.CommandGroup) }
                val client = CommandClient(object : CommandClientHandler {
                    override fun connected() {
                        if (stopped.get() || !measurementStarted.compareAndSet(false, true)) return
                        runCatching {
                            probeWorker.execute {
                                if (!stopped.get()) runMeasurement()
                            }
                        }
                    }
                    override fun disconnected(message: String?) {
                        if (!stopped.get()) fail("Соединение с тестовым движком потеряно")
                    }
                    override fun setDefaultLogLevel(level: Int) = Unit
                    override fun clearLogs() = Unit
                    override fun writeLogs(messageList: LogIterator) = Unit
                    override fun writeStatus(message: StatusMessage?) = Unit
                    override fun writeGroups(message: OutboundGroupIterator) = Unit
                    override fun writeOutbounds(message: OutboundGroupItemIterator) = Unit
                    override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
                    override fun updateClashMode(newMode: String) = Unit
                    override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
                }, options)
                localClient = client
                commandClient = client
                if (!stopped.get()) client.connect()
            } catch (error: Exception) {
                Log.e(TAG, "Throughput engine startup failed (${error.javaClass.simpleName})")
                if (!stopped.get()) fail("Не удалось запустить проверку скорости")
                runCatching { localClient?.disconnect() }
                runCatching { localServer?.closeService() }
                runCatching { localServer?.close() }
            }
        }
        return START_NOT_STICKY
    }

    private fun runMeasurement() {
        val network = physicalNetwork ?: return fail("Физическая сеть недоступна")
        val port = listenPort
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(measurementTimeoutMs)
        try {
            val download = measureDownload(network, port, deadline)
            if (stopped.get()) return
            val upload = measureUpload(network, port, deadline)
            if (stopped.get()) return
            val result = JSONObject()
                .put("profileId", profileId)
                .put("downloadMbps", download.mbps)
                .put("uploadMbps", upload.mbps)
                .put("downloadBytes", download.bytes)
                .put("uploadBytes", upload.bytes)
                .put("durationMs", download.durationMs + upload.durationMs)
                .put("measuredAt", System.currentTimeMillis())
            sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName)
                .putExtra(EXTRA_RESULT, result.toString())
                .putExtra(EXTRA_SESSION, sessionId))
            finish()
        } catch (error: Exception) {
            if (!stopped.get()) {
                Log.w(TAG, "Throughput probe failed (${error.javaClass.simpleName})")
                fail("Не удалось измерить скорость этого сервера")
            }
        }
    }

    private fun measureDownload(network: Network, port: Int, deadline: Long): Sample {
        val url = URL("$DOWNLOAD_URL?bytes=$downloadByteLimit&astra=${System.nanoTime()}")
        val connection = openConnection(network, url, port)
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Cache-Control", "no-store, no-cache")
            connection.connectTimeout = remainingTimeout(deadline)
            connection.readTimeout = remainingTimeout(deadline)
            val startedAt = System.nanoTime()
            val status = connection.responseCode
            require(status in 200..299) { "Download endpoint returned HTTP $status" }
            var total = 0L
            val buffer = ByteArray(32 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    if (stopped.get()) throw InterruptedException("cancelled")
                    if (System.nanoTime() >= deadline) throw java.net.SocketTimeoutException("deadline")
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= downloadByteLimit) { "download byte limit exceeded" }
                }
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(1L)
            require(total > 0L) { "empty download sample" }
            return Sample(total, elapsed, total * 8_000.0 / elapsed / 1_000_000.0)
        } finally {
            connection.disconnect()
            if (activeConnection === connection) activeConnection = null
        }
    }

    private fun measureUpload(network: Network, port: Int, deadline: Long): Sample {
        val payload = ByteArray(uploadByteLimit.toInt()).also { SecureRandom().nextBytes(it) }
        val url = URL(UPLOAD_URL)
        val connection = openConnection(network, url, port)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Cache-Control", "no-store, no-cache")
            connection.connectTimeout = remainingTimeout(deadline)
            connection.readTimeout = remainingTimeout(deadline)
            val startedAt = System.nanoTime()
            connection.outputStream.use { output ->
                var offset = 0
                while (offset < payload.size) {
                    if (stopped.get()) throw InterruptedException("cancelled")
                    if (System.nanoTime() >= deadline) throw java.net.SocketTimeoutException("deadline")
                    val count = minOf(32 * 1024, payload.size - offset)
                    output.write(payload, offset, count)
                    offset += count
                }
                output.flush()
            }
            val status = connection.responseCode
            require(status in 200..299) { "Upload endpoint returned HTTP $status" }
            runCatching { connection.inputStream.close() }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(1L)
            return Sample(payload.size.toLong(), elapsed, payload.size * 8_000.0 / elapsed / 1_000_000.0)
        } finally {
            connection.disconnect()
            if (activeConnection === connection) activeConnection = null
        }
    }

    private fun openConnection(network: Network, url: URL, port: Int): HttpURLConnection {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        return (network.openConnection(url, proxy) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            activeConnection = this
        }
    }

    private fun remainingTimeout(deadline: Long): Int {
        val remaining = ThroughputTimeoutPolicy.remainingMillis(deadline, System.nanoTime())
        if (remaining <= 0) throw java.net.SocketTimeoutException("probe deadline")
        return remaining
    }

    private data class Sample(val bytes: Long, val durationMs: Long, val mbps: Double)

    private fun fail(message: String) {
        mainHandler.post {
            if (stopped.get()) return@post
            publishError(message)
            finish()
        }
    }

    private fun publishError(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName)
            .putExtra(EXTRA_ERROR, message)
            .putExtra(EXTRA_SESSION, sessionId))
    }

    private fun finish() {
        if (!stopped.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(timeoutRunnable)
        val callback = networkCallback
        networkCallback = null
        if (callback != null) {
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(callback)
            }
        }
        val connection = activeConnection
        activeConnection = null
        worker.shutdownNow()
        probeWorker.shutdownNow()
        val client = commandClient
        val server = commandServer
        commandClient = null
        commandServer = null
        Thread({
            runCatching { connection?.disconnect() }
            runCatching { client?.disconnect() }
            runCatching { server?.closeService() }
            runCatching { server?.close() }
        }, "Astra-ThroughputCleanup").apply { isDaemon = true; start() }
        // Service lifecycle and notification operations are serialized on
        // the main thread even when a successful probe finishes on its worker.
        val stopService = Runnable {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopService.run()
        } else {
            mainHandler.post(stopService)
        }
    }

    override fun serviceStop() { mainHandler.post { finish() } }
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) = Unit
    override fun connectSSHAgent(): Int = -1

    override fun onDestroy() {
        finish()
        worker.shutdownNow()
        probeWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.action.START_THROUGHPUT_PROBE"
        const val ACTION_STOP = "com.astra.vpn.action.STOP_THROUGHPUT_PROBE"
        const val ACTION_RESULT = "com.astra.vpn.action.THROUGHPUT_RESULT"
        const val ACTION_ERROR = "com.astra.vpn.action.THROUGHPUT_ERROR"
        const val EXTRA_CONFIG = "com.astra.vpn.extra.THROUGHPUT_CONFIG"
        const val EXTRA_NETWORK = "com.astra.vpn.extra.THROUGHPUT_NETWORK"
        const val EXTRA_PROFILE = "com.astra.vpn.extra.THROUGHPUT_PROFILE"
        const val EXTRA_PORT = "com.astra.vpn.extra.THROUGHPUT_PORT"
        const val EXTRA_SESSION = "com.astra.vpn.extra.THROUGHPUT_SESSION"
        const val EXTRA_RESULT = "com.astra.vpn.extra.THROUGHPUT_RESULT"
        const val EXTRA_ERROR = "com.astra.vpn.extra.THROUGHPUT_ERROR"
        const val EXTRA_FAST_MODE = "com.astra.vpn.extra.THROUGHPUT_FAST_MODE"
        private const val TAG = "AstraThroughputProbe"
        private const val NOTIFICATION_ID = 3
        private const val TOTAL_TIMEOUT_MS = 20_000L
        private const val DOWNLOAD_BYTES = 8_000_000L
        private const val UPLOAD_BYTES = 2_000_000L
        private const val FAST_TIMEOUT_MS = 8_000L
        private const val FAST_DOWNLOAD_BYTES = 2_000_000L
        private const val FAST_UPLOAD_BYTES = 512_000L
        private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
        private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"

        fun start(
            context: android.content.Context,
            config: String,
            network: Network,
            profileId: String,
            port: Int,
            sessionId: String,
            fastMode: Boolean = false
        ) {
            val intent = Intent(context, AstraThroughputProbeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_NETWORK, network)
                .putExtra(EXTRA_PROFILE, profileId)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_FAST_MODE, fastMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
