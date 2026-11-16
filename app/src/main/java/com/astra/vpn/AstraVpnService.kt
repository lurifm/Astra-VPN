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
            Log.e(TAG, "Не передана конфигурация sing-box")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (commandServer != null) {
            runCatching {
                commandServer?.startOrReloadService(config, OverrideOptions())
            }.onFailure {
                Log.e(TAG, "Не удалось перезапустить VPN", it)
                stopTunnel()
            }
            return START_NOT_STICKY
        }

        try {
            val platform = AstraPlatform(this) { descriptor ->
                tunDescriptor?.close()
                tunDescriptor = descriptor
            }
            val server = CommandServer(this, platform)
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            commandServer = server
            Log.i(TAG, "sing-box VPN tunnel started")
        } catch (error: Exception) {
            Log.e(TAG, "Не удалось запустить sing-box VPN", error)
            stopTunnel()
        }

        return START_NOT_STICKY
    }

    override fun serviceStop() {
        stopTunnel()
    }

    override fun serviceReload() {
        // Profile reloads are initiated by the Activity with a fresh config.
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message.orEmpty())
    }

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
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed", it) }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer close failed", it) }
        }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.action.START"
        const val ACTION_STOP = "com.astra.vpn.action.STOP"
        const val EXTRA_CONFIG = "com.astra.vpn.extra.CONFIG"

        private const val TAG = "AstraVpnService"
        private const val NOTIFICATION_ID = 1
    }
}
