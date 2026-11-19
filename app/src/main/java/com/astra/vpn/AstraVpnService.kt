package com.astra.vpn

import android.content.Intent
import android.app.NotificationManager
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

class AstraVpnService : VpnService(), CommandServerHandler {
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var activeConfig: String? = null
    private var activeProfileName: String = "Сервер не выбран"
    private var activeProtocol: String = "—"
    private var lastHistoryState: String? = null
    // Prevent callbacks from closeService() from re-entering stopTunnel().
    private var stopping = false
    private val lifecycleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthExecutor = Executors.newSingleThreadExecutor()
    private val healthProbeInFlight = AtomicBoolean(false)
    @Volatile private var healthProbeTask: FutureTask<Unit>? = null
    private val healthTick = object : Runnable {
        override fun run() {
            synchronized(lifecycleLock) {
                if (stopping || tunDescriptor == null) return
            }
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            if (network != null && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                healthProbeInFlight.compareAndSet(false, true)
            ) {
                val task = FutureTask<Unit> {
                    try {
                        val reachable = runCatching { TunnelHealthChecker.check(network) }.getOrDefault(false)
                        val currentNetwork = connectivity.activeNetwork
                        val currentCapabilities = currentNetwork?.let(connectivity::getNetworkCapabilities)
                        val serviceActive = synchronized(lifecycleLock) {
                            !stopping && tunDescriptor != null
                        }
                        val sameVpnNetwork = currentNetwork == network &&
                            currentCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                        if (ServiceHealthResultPolicy.shouldPublish(serviceActive, sameVpnNetwork)) {
                            val result = Intent(ACTION_HEALTH).setPackage(packageName)
                                .putExtra(EXTRA_HEALTH_REACHABLE, reachable)
                                .putExtra(EXTRA_HEALTH_CHECKED_AT, android.os.SystemClock.elapsedRealtime())
                            sendBroadcast(result)
                        }
                    } finally {
                        healthProbeInFlight.set(false)
                        healthProbeTask = null
                    }
                }
                healthProbeTask = task
                runCatching { healthExecutor.execute(task) }
                    .onFailure {
                        healthProbeTask = null
                        healthProbeInFlight.set(false)
                    }
            }
            mainHandler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    private fun startHealthMonitoring() {
        mainHandler.removeCallbacks(healthTick)
        mainHandler.postDelayed(healthTick, HEALTH_INTERVAL_MS)
    }

    private fun stopHealthMonitoring() {
        mainHandler.removeCallbacks(healthTick)
        healthProbeTask?.cancel(true)
        healthProbeTask = null
        healthProbeInFlight.set(false)
    }
    private val tunTimeout = Runnable {
        synchronized(lifecycleLock) {
            if (!stopping && commandServer != null && tunDescriptor == null) {
                val message = "sing-box не создал VPN-интерфейс за отведённое время"
                Log.e(TAG, message)
                stopTunnel()
                publishState(STATE_ERROR, message)
            }
        }
    }

    private fun awaitTunCreation() {
        mainHandler.removeCallbacks(tunTimeout)
        mainHandler.postDelayed(tunTimeout, TUN_TIMEOUT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Serialize a new start with asynchronous libbox callbacks and shutdown.
        synchronized(lifecycleLock) { stopping = false }
        intent?.getStringExtra(EXTRA_PROFILE_NAME)?.takeIf { it.isNotBlank() }?.let { activeProfileName = it }
        intent?.getStringExtra(EXTRA_PROTOCOL)?.takeIf { it.isNotBlank() }?.let { activeProtocol = it }
        // A new start command may be a server switch that reuses the existing TUN.
        if (intent?.action == ACTION_START) lastHistoryState = null
        startForeground(
            NOTIFICATION_ID,
            AstraNotification.create(this, STATE_CONNECTING, activeProfileName)
        )
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        if (config.isNullOrBlank()) {
            val message = "Не передана конфигурация sing-box"
            Log.e(TAG, message)
            stopTunnel()
            publishState(STATE_ERROR, message)
            return START_NOT_STICKY
        }
        if (commandServer != null) {
            runCatching { commandServer?.startOrReloadService(config, OverrideOptions()) }
                .onSuccess {
                    activeConfig = config
                    if (tunDescriptor == null) {
                        publishState(STATE_CONNECTING)
                        awaitTunCreation()
                    } else {
                        // A reload can reuse an already established TUN. In that
                        // case no new openTun callback is guaranteed, so restore
                        // the connected state explicitly instead of leaving the UI
                        // stuck on "connecting".
                        publishState(STATE_CONNECTED)
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Не удалось перезапустить VPN (" + error.javaClass.simpleName + ")")
                    stopTunnel()
                    publishState(STATE_ERROR, "Не удалось перезапустить VPN. Проверь выбранный сервер и подключение.")
                }
            return START_NOT_STICKY
        }
        try {
            val platform = AstraPlatform(this, { descriptor ->
                handleTunCreated(descriptor)
            })
            val server = CommandServer(this, platform)
            commandServer = server
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            activeConfig = config
            Log.i(TAG, "sing-box service accepted configuration")
            if (tunDescriptor == null) {
                publishState(STATE_CONNECTING)
                awaitTunCreation()
                Log.w(TAG, "sing-box accepted config; waiting for Android TUN creation")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось запустить sing-box VPN (" + error.javaClass.simpleName + ")")
            stopTunnel()
            publishState(STATE_ERROR, "Не удалось запустить VPN. Проверь конфигурацию сервера и разрешение на VPN.")
        }
        return START_NOT_STICKY
    }

    override fun serviceStop() = stopTunnel()

    override fun serviceReload() {
        val config = activeConfig ?: return
        val server = commandServer ?: return
        runCatching { server.startOrReloadService(config, OverrideOptions()) }
            .onSuccess {
                if (tunDescriptor == null) {
                    publishState(STATE_CONNECTING)
                    awaitTunCreation()
                } else {
                    publishState(STATE_CONNECTED)
                }
            }
            .onFailure { error ->
                Log.e(TAG, "Не удалось перезагрузить конфигурацию sing-box (" + error.javaClass.simpleName + ")")
                stopTunnel()
                publishState(STATE_ERROR, "Не удалось обновить VPN. Проверь конфигурацию сервера.")
            }
    }
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) {
        // libbox diagnostics may contain server addresses or other connection details.
        // Keep the callback observable without writing its raw payload to logcat.
        Log.d(TAG, "sing-box diagnostic message received")
    }
    override fun connectSSHAgent(): Int = -1

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        // stopTunnel() already releases every resource and publishes the final
        // state before stopSelf(). Do not run it a second time here: a second
        // DISCONNECTED broadcast could overwrite a preceding ERROR state that
        // the Activity needs to display after a failed connection attempt.
        if (commandServer != null || tunDescriptor != null || activeConfig != null) {
            stopTunnel()
        }
        healthExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleTunCreated(descriptor: ParcelFileDescriptor) {
        synchronized(lifecycleLock) {
            // libbox may finish opening TUN while shutdown is already underway.
            // Never resurrect a stopped service or publish CONNECTED in that race.
            if (stopping) {
                runCatching { descriptor.close() }
                    .onFailure { Log.w(TAG, "Late TUN descriptor close failed (" + it.javaClass.simpleName + ")") }
                return
            }
            runCatching { tunDescriptor?.close() }
            tunDescriptor = descriptor
            mainHandler.removeCallbacks(tunTimeout)
            publishState(STATE_CONNECTED)
            startHealthMonitoring()
        }
    }

    private fun stopTunnel(): Unit = synchronized(lifecycleLock) {
        if (stopping) return@synchronized
        stopping = true
        // Match the upstream sing-box Android lifecycle: release the Android
        // TUN descriptor, then close the service and finally the command server.
        mainHandler.removeCallbacks(tunTimeout)
        stopHealthMonitoring()
        val server = commandServer
        commandServer = null
        activeConfig = null

        runCatching { tunDescriptor?.close() }
            .onFailure { Log.w(TAG, "TUN descriptor close failed (" + it.javaClass.simpleName + ")") }
        tunDescriptor = null

        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed (" + it.javaClass.simpleName + ")") }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer close failed (" + it.javaClass.simpleName + ")") }
        }
        publishState(STATE_DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishState(state: String, error: String? = null) {
        // Save the latest state so the Activity can restore its UI after
        // recreation instead of depending on a broadcast it may have missed.
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putString(PREF_STATE, state)
            .putString(PREF_ERROR, error)
            .putLong(PREF_UPDATED_AT, System.currentTimeMillis())
            .apply()
        val historyState = when (state) {
            STATE_CONNECTING -> "connecting"
            STATE_CONNECTED -> "connected"
            STATE_DISCONNECTED -> "disconnected"
            STATE_ERROR -> "error"
            else -> null
        }
        if (historyState != null && historyState != lastHistoryState) {
            runCatching {
                ConnectionHistoryStore(this).record(historyState, activeProfileName, activeProtocol)
            }.onFailure { Log.w(TAG, "Не удалось сохранить историю подключений (" + it.javaClass.simpleName + ")") }
            lastHistoryState = historyState
        }
        val intent = Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, state)
        if (error != null) intent.putExtra(EXTRA_ERROR, error)
        sendBroadcast(intent)
        runCatching {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(
                NOTIFICATION_ID,
                AstraNotification.create(this, state, activeProfileName)
            )
        }.onFailure { Log.w(TAG, "Не удалось обновить уведомление VPN (" + it.javaClass.simpleName + ")") }
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.action.START"
        const val ACTION_STOP = "com.astra.vpn.action.STOP"
        const val ACTION_STATE = "com.astra.vpn.action.STATE"
        const val ACTION_HEALTH = "com.astra.vpn.action.HEALTH"
        const val EXTRA_HEALTH_REACHABLE = "com.astra.vpn.extra.HEALTH_REACHABLE"
        const val EXTRA_HEALTH_CHECKED_AT = "com.astra.vpn.extra.HEALTH_CHECKED_AT"
        const val EXTRA_CONFIG = "com.astra.vpn.extra.CONFIG"
        const val EXTRA_PROFILE_NAME = "com.astra.vpn.extra.PROFILE_NAME"
        const val EXTRA_PROTOCOL = "com.astra.vpn.extra.PROTOCOL"
        const val EXTRA_STATE = "com.astra.vpn.extra.STATE"
        const val EXTRA_ERROR = "com.astra.vpn.extra.ERROR"
        const val STATE_CONNECTED = "connected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"
        const val STATE_PREFS = "astra_vpn_state"
        const val PREF_STATE = "state"
        const val PREF_ERROR = "error"
        const val PREF_UPDATED_AT = "updated_at"
        private const val TAG = "AstraVpnService"
        private const val NOTIFICATION_ID = 1
        private const val TUN_TIMEOUT_MS = 20_000L
        private const val HEALTH_INTERVAL_MS = 12_000L
    }
}
