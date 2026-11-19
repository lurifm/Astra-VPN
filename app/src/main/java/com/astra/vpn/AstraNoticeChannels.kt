package com.astra.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Notification channel foundation for subscription and traffic warnings. */
internal object AstraNoticeChannels {
    const val SUBSCRIPTIONS = "astra_subscription_alerts"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            SUBSCRIPTIONS,
            context.getString(R.string.subscription_alerts_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.subscription_alerts_description)
        }
        manager.createNotificationChannel(channel)
    }
}
