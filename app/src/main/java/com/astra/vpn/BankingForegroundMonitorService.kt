package com.astra.vpn

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * User-started foreground observer for banking automation.
 *
 * This service only reports a conservative foreground-package observation.
 * It does not pause, stop, or restart the VPN tunnel by itself.
 */
internal class BankingForegroundMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val activePackages = linkedSetOf<String>()
    private var lastQueryTime = 0L
    private var lastPublishedPackage: String? = null
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val observed = runCatching { readForegroundPackage() }
                .onFailure { Log.w(TAG, "Usage events unavailable", it) }
                .getOrNull()
            if (observed != lastPublishedPackage) {
                lastPublishedPackage = observed
                sendBroadcast(
                    Intent(ACTION_OBSERVATION)
                        .setPackage(packageName)
                        .putExtra(EXTRA_FOREGROUND_PACKAGE, observed)
                        .putExtra(EXTRA_OBSERVED_AT, System.currentTimeMillis())
                )
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                handler.removeCallbacksAndMessages(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!UsageAccessPermission.hasAccess(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                if (!running) {
                    running = true
                    lastQueryTime = (System.currentTimeMillis() - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
                    handler.post(poll)
                }
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("Astra VPN · защита банковских приложений")
            .setContentText("Мониторинг приложений включён")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun readForegroundPackage(): String? {
        if (!UsageAccessPermission.hasAccess(this)) return null
        val manager = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val begin = (lastQueryTime - 1L).coerceAtLeast(0L)
        val events = manager.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName?.takeIf { it.isNotBlank() } ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> activePackages.add(pkg)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> activePackages.remove(pkg)
            }
        }
        lastQueryTime = now
        // Multi-window / split-screen can have more than one resumed package.
        // Returning null is safer than guessing which app is actually foreground.
        return activePackages.singleOrNull()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Банковская защита",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Показывает, когда включён мониторинг банковских приложений." }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.banking_monitor.START"
        const val ACTION_STOP = "com.astra.vpn.banking_monitor.STOP"
        const val ACTION_OBSERVATION = "com.astra.vpn.banking_monitor.OBSERVATION"
        const val EXTRA_FOREGROUND_PACKAGE = "foreground_package"
        const val EXTRA_OBSERVED_AT = "observed_at"
        private const val TAG = "AstraBankMonitor"
        private const val CHANNEL_ID = "astra_banking_monitor"
        private const val NOTIFICATION_ID = 43
        private const val POLL_INTERVAL_MS = 1_000L
        private const val INITIAL_LOOKBACK_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, BankingForegroundMonitorService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BankingForegroundMonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

/** Usage Access is a user-granted AppOps capability, not a runtime permission dialog. */
internal object UsageAccessPermission {
    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
