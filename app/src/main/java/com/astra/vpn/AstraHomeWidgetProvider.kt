package com.astra.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Simple home-screen control. The tap is routed through MainActivity and Android's VPN consent flow. */
class AstraHomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AstraHomeWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.astra_home_widget)
            val state = context.getSharedPreferences(AstraVpnService.STATE_PREFS, Context.MODE_PRIVATE)
                .getString(AstraVpnService.PREF_STATE, AstraVpnService.STATE_DISCONNECTED)
            val statusLabel = when (state) {
                AstraVpnService.STATE_CONNECTED -> R.string.widget_status_connected
                AstraVpnService.STATE_CONNECTING -> R.string.widget_status_connecting
                AstraVpnService.STATE_ERROR -> R.string.widget_status_error
                else -> R.string.widget_status_disconnected
            }
            views.setTextViewText(R.id.astra_widget_status, context.getString(statusLabel))
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
            launchIntent.action = MainActivity.ACTION_TILE_TOGGLE
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.astra_widget_button, pendingIntent)
            manager.updateAppWidget(widgetId, views)
        }
    }
}
