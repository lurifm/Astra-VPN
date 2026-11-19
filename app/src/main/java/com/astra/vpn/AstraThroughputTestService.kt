package com.astra.vpn

import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns a short-lived sing-box instance used only for bounded throughput probes.
 * It deliberately has no TUN inbound and binds outbound sockets to the selected
 * physical Android Network. It never establishes or reloads the user's VPN.
 */
internal class AstraThroughputTestService : VpnService(), CommandServerHandler {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    private var server: CommandServer? = null
    private var probes: Map<String, Int> = emptyMap()
    private val timeout = Runnable { publishFailure("Превышено время измерения скорости"); finish() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (server != null) return START_NOT_STICKY

        val config = intent?.getStringExtra(EXTRA_CONFIG)
        val probesJson = intent?.getStringExtra(EXTRA_PROBES)
        val physicalNetwork = intent?.getParcelableExtra<Network>(EXTRA_NETWORK)
        if (config.isNullOrBlank() || probesJson.isNullOrBlank() || physicalNetwork == null) {
            publishFailure("Не передана конфигурация или физическая сеть")
            finish()
            return START_NOT_STICKY
        }
        probes = runCatching {
            val json = JSONObject(probesJson)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val tag = keys.next()
                    put(tag, json.getInt(tag))
                }
            }
        }.getOrDefault(emptyMap())
        if (probes.isEmpty() || probes.size > MAX_PROBES || probes.values.any { it !in 1..65535 }) {
            publishFailure("Некорректный список серверов для измерения")
            finish()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            AstraNotification.create(this, AstraVpnService.STATE_CONNECTING, "Измерение скорости")
        )
        try {
            val platform = AstraPlatform(
                this,
                { descriptor ->
                    runCatching { descriptor.close() }
                    publishFailure("Измерение неожиданно запросило VPN-интерфейс")
                    handler.post { finish() }
                },
                protectSockets = false,
                preferredPhysicalNetwork = physicalNetwork,
                manageVpnUnderlyingNetworks = false
            )
            val commandServer = CommandServer(this, platform)
            server = commandServer
            commandServer.start()
            commandServer.startOrReloadService(config, OverrideOptions())
            handler.postDelayed(timeout, TOTAL_TIMEOUT_MS)
            worker.execute { runProbes() }
        } catch (failure: Exception) {
            Log.e(TAG, "Не удалось запустить изолированное измерение (" + failure.javaClass.simpleName + ")")
            publishFailure("Не удалось запустить измерение скорости")
            finish()
        }
        return START_NOT_STICKY
    }

    private fun runProbes() {
        val results = JSONObject()
        try {
            for ((tag, port) in probes) {
                if (stopped.get()) return
                if (!awaitProxy(port)) {
                    results.put(tag, JSONObject.NULL)
                    continue
                }
                val sample = ThroughputDownloadProbe.measure(port)
                results.put(tag, sample?.downloadMbps ?: JSONObject.NULL)
                publishProgress(results)
            }
            if (!stopped.get()) {
                sendBroadcast(
                    Intent(ACTION_RESULTS)
                        .setPackage(packageName)
                        .putExtra(EXTRA_RESULTS, results.toString())
                        .putExtra(EXTRA_FINAL, true)
                )
                finish()
            }
        } catch (failure: Exception) {
            if (!stopped.get()) {
                Log.w(TAG, "Ошибка измерения скорости (" + failure.javaClass.simpleName + ")")
                publishFailure("Не удалось завершить измерение скорости")
                handler.post { finish() }
            }
        }
    }

    /** Wait until sing-box has bound the loopback mixed inbound before HTTPS CONNECT. */
    private fun awaitProxy(port: Int): Boolean {
        val deadline = System.nanoTime() + PROXY_READY_TIMEOUT_NANOS
        while (!stopped.get() && System.nanoTime() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
                return true
            } catch (_: Exception) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { return false }
            }
        }
        return false
    }

    private fun publishProgress(results: JSONObject) {
        sendBroadcast(
            Intent(ACTION_RESULTS)
                .setPackage(packageName)
                .putExtra(EXTRA_RESULTS, results.toString())
                .putExtra(EXTRA_FINAL, false)
        )
    }

    private fun publishFailure(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_ERROR, message))
    }

    private fun finish() {
        if (!stopped.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        val currentServer = server
        server = null
        if (currentServer != null) {
            runCatching { currentServer.closeService() }
            runCatching { currentServer.close() }
        }
        worker.shutdownNow()
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
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.action.START_THROUGHPUT_TEST"
        const val ACTION_STOP = "com.astra.vpn.action.STOP_THROUGHPUT_TEST"
        const val ACTION_RESULTS = "com.astra.vpn.action.THROUGHPUT_RESULTS"
        const val ACTION_ERROR = "com.astra.vpn.action.THROUGHPUT_ERROR"
        const val EXTRA_CONFIG = "com.astra.vpn.extra.THROUGHPUT_CONFIG"
        const val EXTRA_PROBES = "com.astra.vpn.extra.THROUGHPUT_PROBES"
        const val EXTRA_NETWORK = "com.astra.vpn.extra.THROUGHPUT_NETWORK"
        const val EXTRA_RESULTS = "com.astra.vpn.extra.THROUGHPUT_RESULTS"
        const val EXTRA_FINAL = "com.astra.vpn.extra.THROUGHPUT_FINAL"
        const val EXTRA_ERROR = "com.astra.vpn.extra.THROUGHPUT_ERROR"
        private const val TAG = "AstraThroughputTest"
        private const val NOTIFICATION_ID = 3
        private const val MAX_PROBES = 64
        private const val PROXY_READY_TIMEOUT_NANOS = 8_000_000_000L
        private const val TOTAL_TIMEOUT_MS = 180_000L

        fun start(context: Context, config: String, portsByTag: Map<String, Int>, network: Network) {
            val ports = JSONObject()
            portsByTag.forEach { (tag, port) -> ports.put(tag, port) }
            val intent = Intent(context, AstraThroughputTestService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_PROBES, ports.toString())
                .putExtra(EXTRA_NETWORK, network)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
