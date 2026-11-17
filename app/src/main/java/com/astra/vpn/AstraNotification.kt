package com.astra.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

object AstraNotification {

    private const val CHANNEL_ID = "astra_vpn_channel"

    fun create(
        context: Context,
        state: String = "Служба работает",
        serverName: String = ""
    ): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Astra VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val title = when (state) {
            AstraVpnService.STATE_CONNECTING -> "Подключение…"
            AstraVpnService.STATE_CONNECTED -> "VPN подключён"
            AstraVpnService.STATE_ERROR -> "Ошибка VPN"
            else -> "Astra VPN"
        }
        val detail = when (state) {
            AstraVpnService.STATE_CONNECTING -> "Устанавливаем защищённое соединение"
            AstraVpnService.STATE_CONNECTED -> if (serverName.isNotBlank()) {
                "Защищённое соединение · $serverName"
            } else "Защищённое соединение активно"
            AstraVpnService.STATE_ERROR -> "Не удалось установить соединение"
            AstraVpnService.STATE_DISCONNECTED -> "VPN отключён"
            else -> if (serverName.isNotBlank()) serverName else "Служба работает"
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                R.drawable.ic_vpn
            } else {
                android.R.drawable.stat_sys_warning
            })
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(state == AstraVpnService.STATE_CONNECTING ||
                state == AstraVpnService.STATE_CONNECTED || state == "Служба работает")
            .build()
    }
}
