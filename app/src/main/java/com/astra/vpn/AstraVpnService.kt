package com.astra.vpn

import android.content.Intent
import android.app.NotificationManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONObject
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus

class AstraVpnService : VpnService(), CommandServerHandler {
    private var commandServer: CommandServer? = null
    private var trafficMonitor: LibboxTrafficMonitor? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var activeConfig: String? = null
    private var activeProfileName: String = "Сервер не выбран"
    private var activeProtocol: String = "—"
    private var lastHistoryState: String? = null
    // Prevent callbacks from closeService() from re-entering stopTunnel().
    private var stopping = false
    // Reject TUN callbacks originating from a CommandServer instance that has been stopped.
    private val lifecycleGeneration = ServiceLifecycleGeneration()
    private val lifecycleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    // A distinct token is required for every TUN wait, including reloads that
    // reuse the same CommandServer and therefore the same lifecycle generation.
    private val tunTimeoutGeneration = TunCreationTimeoutGeneration()
    private var tunTimeout: Runnable? = null

    private fun awaitTunCreation() = synchronized(lifecycleLock) {
        cancelTunTimeoutLocked()
        val ownerToken = tunTimeoutGeneration.next()
        val timeout = Runnable {
            synchronized(lifecycleLock) {
                if (!tunTimeoutGeneration.isCurrent(ownerToken)) return@synchronized
                tunTimeout = null
                if (!stopping && commandServer != null && tunDescriptor == null) {
                    val message = "sing-box не создал VPN-интерфейс за отведённое время"
                    Log.e(TAG, message)
                    stopTunnel()
                    publishState(STATE_ERROR, message)
                }
            }
        }
        tunTimeout = timeout
        mainHandler.postDelayed(timeout, TUN_TIMEOUT_MS)
    }

    private fun cancelTunTimeoutLocked() {
        tunTimeoutGeneration.invalidate()
        tunTimeout?.let(mainHandler::removeCallbacks)
        tunTimeout = null
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
                    startTrafficMonitor()
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
            val ownerGeneration = lifecycleGeneration.advance()
            val platform = AstraPlatform(this, { descriptor ->
                handleTunCreated(descriptor, ownerGeneration)
            })
            val server = CommandServer(this, platform)
            commandServer = server
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            activeConfig = config
            startTrafficMonitor()
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
        // Capture a consistent server/config pair, but never hold lifecycleLock
        // while calling into libbox: that call may wait for callbacks which also
        // need the lifecycle lock.
        val reload = synchronized(lifecycleLock) {
            if (stopping) return
            val config = activeConfig ?: return
            val server = commandServer ?: return
            server to config
        }
        val (server, config) = reload
        runCatching { server.startOrReloadService(config, OverrideOptions()) }
            .onSuccess {
                synchronized(lifecycleLock) {
                    if (stopping || commandServer !== server || activeConfig != config) return@synchronized
                    startTrafficMonitor()
                    if (tunDescriptor == null) {
                        publishState(STATE_CONNECTING)
                        awaitTunCreation()
                    } else {
                        publishState(STATE_CONNECTED)
                    }
                }
            }
            .onFailure { error ->
                synchronized(lifecycleLock) {
                    if (stopping || commandServer !== server || activeConfig != config) return@synchronized
                    Log.e(TAG, "Не удалось перезагрузить конфигурацию sing-box (" + error.javaClass.simpleName + ")")
                    stopTunnel()
                    publishState(STATE_ERROR, "Не удалось обновить VPN. Проверь конфигурацию сервера.")
                }
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
        super.onDestroy()
    }

    private fun handleTunCreated(descriptor: ParcelFileDescriptor, ownerGeneration: Long) {
        synchronized(lifecycleLock) {
            // libbox may finish opening TUN after shutdown or after a replacement
            // CommandServer has started. Never let an older server publish CONNECTED.
            if (stopping || !lifecycleGeneration.isCurrent(ownerGeneration)) {
                runCatching { descriptor.close() }
                    .onFailure { Log.w(TAG, "Late TUN descriptor close failed (" + it.javaClass.simpleName + ")") }
                return
            }
            runCatching { tunDescriptor?.close() }
            tunDescriptor = descriptor
            cancelTunTimeoutLocked()
            publishState(STATE_CONNECTED)
        }
    }

    private fun stopTunnel(): Unit = synchronized(lifecycleLock) {
        if (stopping) return@synchronized
        stopping = true
        lifecycleGeneration.advance()
        // Match the upstream sing-box Android lifecycle: release the Android
        // TUN descriptor, then close the service and finally the command server.
        cancelTunTimeoutLocked()
        val server = commandServer
        commandServer = null
        trafficMonitor?.stop()
        trafficMonitor = null
        publishTrafficSnapshot(emptyMap())
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

    private fun startTrafficMonitor() {
        if (trafficMonitor != null) return
        val monitor = LibboxTrafficMonitor(::publishTrafficSnapshot)
        trafficMonitor = monitor
        monitor.start()
    }

    private fun publishTrafficSnapshot(snapshot: Map<String, LibboxTrafficAccumulator.Totals>) {
        val payload = JSONObject()
        snapshot.forEach { (tag, totals) ->
            payload.put(tag, JSONObject()
                .put("uploadBytes", totals.uploadBytes)
                .put("downloadBytes", totals.downloadBytes))
        }
        getSharedPreferences(TRAFFIC_PREFS, MODE_PRIVATE).edit()
            .putString(TRAFFIC_JSON, payload.toString())
            .putLong(TRAFFIC_UPDATED_AT, System.currentTimeMillis())
            .apply()
        sendBroadcast(
            Intent(ACTION_TRAFFIC).setPackage(packageName)
                .putExtra(EXTRA_TRAFFIC_JSON, payload.toString())
        )
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
        const val ACTION_TRAFFIC = "com.astra.vpn.action.TRAFFIC"
        const val EXTRA_TRAFFIC_JSON = "com.astra.vpn.extra.TRAFFIC_JSON"
        const val TRAFFIC_PREFS = "astra_libbox_traffic"
        const val TRAFFIC_JSON = "outbound_traffic_json"
        const val TRAFFIC_UPDATED_AT = "updated_at"
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
    }
}
