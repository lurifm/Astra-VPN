package com.astra.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus

class AstraVpnService : VpnService(), CommandServerHandler {
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null

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
                .onSuccess { publishState(STATE_CONNECTED) }
                .onFailure {
                    Log.e(TAG, "Не удалось перезапустить VPN", it)
                    stopTunnel()
                    publishState(STATE_ERROR, it.message ?: "Не удалось перезапустить VPN")
                }
            return START_NOT_STICKY
        }
        try {
            val platform = AstraPlatform(this) { descriptor ->
                tunDescriptor?.close()
                tunDescriptor = descriptor
            }
            val server = CommandServer(this, platform)
            commandServer = server
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            Log.i(TAG, "sing-box VPN tunnel started")
            publishState(STATE_CONNECTED)
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось запустить sing-box VPN", error)
            stopTunnel()
            publishState(STATE_ERROR, error.message ?: "Не удалось запустить VPN")
        }
        return START_NOT_STICKY
    }

    override fun serviceStop() = stopTunnel()
    override fun serviceReload() = Unit
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
        // Let sing-box stop using the TUN file descriptor before closing our
        // ParcelFileDescriptor wrapper. Closing it too early can interrupt teardown.
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed", it) }
        }

        runCatching { tunDescriptor?.close() }
            .onFailure { Log.w(TAG, "TUN descriptor close failed", it) }
        tunDescriptor = null

        if (server != null) {
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer close failed", it) }
        }
        publishState(STATE_DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishState(state: String, error: String? = null) {
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
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"
        private const val TAG = "AstraVpnService"
        private const val NOTIFICATION_ID = 1
    }
}
