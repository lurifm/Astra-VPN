package com.astra.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object AstraNotification {

    private const val CHANNEL_ID = "astra_vpn_channel"

    fun create(context: Context): Notification {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Astra VPN",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = context.getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Astra VPN")
                .setContentText("Служба работает")
                .setSmallIcon(R.drawable.ic_vpn)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle("Astra VPN")
                .setContentText("Служба работает")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build()
        }
    }
}
