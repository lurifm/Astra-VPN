package com.astra.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus

class AstraVpnService : VpnService(), CommandServerHandler {
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var activeConfig: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tunTimeout = Runnable {
        if (commandServer != null && tunDescriptor == null) {
            val message = "sing-box не создал VPN-интерфейс за отведённое время"
            Log.e(TAG, message)
            stopTunnel()
            publishState(STATE_ERROR, message)
        }
    }

    private fun awaitTunCreation() {
        mainHandler.removeCallbacks(tunTimeout)
        mainHandler.postDelayed(tunTimeout, TUN_TIMEOUT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, AstraNotification.create(this))
        }
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
                    }
                }
                .onFailure {
                    Log.e(TAG, "Не удалось перезапустить VPN", it)
                    stopTunnel()
                    publishState(STATE_ERROR, it.message ?: "Не удалось перезапустить VPN")
                }
            return START_NOT_STICKY
        }
        try {
            val platform = AstraPlatform(this) { descriptor ->
                runCatching { tunDescriptor?.close() }
                tunDescriptor = descriptor
                mainHandler.removeCallbacks(tunTimeout)
                // Publish only after Android has actually established the TUN.
                publishState(STATE_CONNECTED)
            }
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
            Log.e(TAG, "Не удалось запустить sing-box VPN", error)
            stopTunnel()
            publishState(STATE_ERROR, error.message ?: "Не удалось запустить VPN")
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
                }
            }
            .onFailure {
                Log.e(TAG, "Не удалось перезагрузить конфигурацию sing-box", it)
                stopTunnel()
                publishState(STATE_ERROR, it.message ?: "Не удалось обновить VPN")
            }
    }
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) { Log.d(TAG, message.orEmpty()) }
    override fun connectSSHAgent(): Int = -1

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun stopTunnel() {
        // Match the upstream sing-box Android lifecycle: release the Android
        // TUN descriptor, then close the service and finally the command server.
        mainHandler.removeCallbacks(tunTimeout)
        val server = commandServer
        commandServer = null
        activeConfig = null

        runCatching { tunDescriptor?.close() }
            .onFailure { Log.w(TAG, "TUN descriptor close failed", it) }
        tunDescriptor = null

        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed", it) }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer close failed", it) }
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
            .apply()
        val intent = Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, state)
        if (error != null) intent.putExtra(EXTRA_ERROR, error)
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.action.START"
        const val ACTION_STOP = "com.astra.vpn.action.STOP"
        const val ACTION_STATE = "com.astra.vpn.action.STATE"
        const val EXTRA_CONFIG = "com.astra.vpn.extra.CONFIG"
        const val EXTRA_STATE = "com.astra.vpn.extra.STATE"
        const val EXTRA_ERROR = "com.astra.vpn.extra.ERROR"
        const val STATE_CONNECTED = "connected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"
        const val STATE_PREFS = "astra_vpn_state"
        const val PREF_STATE = "state"
        const val PREF_ERROR = "error"
        private const val TAG = "AstraVpnService"
        private const val NOTIFICATION_ID = 1
        private const val TUN_TIMEOUT_MS = 20_000L
    }
}
