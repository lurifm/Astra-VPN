package com.astra.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Periodic refresh continues when the activity is closed; WorkManager handles Doze and reboot. */
class SubscriptionRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val settings = applicationContext.getSharedPreferences("astra_settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean("auto_update", false)) return Result.success()
        return try {
            val store = ProfileStore(applicationContext)
            val sources = store.subscriptionSources()
            if (sources.isEmpty()) {
                settings.edit().remove("subscription_retry_sources").remove("subscription_known_sources").apply()
                return Result.success()
            }
            // Retry only providers that failed previously. Re-downloading successful
            // subscriptions on every WorkManager backoff wastes bandwidth and can
            // unnecessarily hit provider rate limits.
            val pending = settings.getStringSet("subscription_retry_sources", emptySet()).orEmpty().toSet()
            val known = settings.getStringSet("subscription_known_sources", emptySet()).orEmpty().toSet()
            val targets = SubscriptionRefreshTargets.select(sources, pending, known)
            if (targets.isEmpty()) {
                settings.edit().remove("subscription_retry_sources").apply()
                return Result.success()
            }
            settings.edit().putLong("last_subscription_attempt", System.currentTimeMillis()).apply()
            var updated = 0
            var serverCount = 0
            var failures = 0
            val failedSources = linkedSetOf<String>()
            targets.forEach { source ->
                try {
                    val count = SubscriptionRefreshCoordinator.refresh(store, source)
                    updated++
                    serverCount += count
                } catch (_: Exception) {
                    failures++
                    failedSources += source
                }
            }
            settings.edit().apply {
                if (failedSources.isEmpty()) remove("subscription_retry_sources")
                else putStringSet("subscription_retry_sources", failedSources)
                // Mark sources as seen only after selected refreshes finish.
                putStringSet("subscription_known_sources", sources.toSet())
            }.apply()
            if (updated > 0) {
                settings.edit().putLong("last_subscription_update", System.currentTimeMillis()).apply()
            }
            val title = if (failures == 0) "Подписки обновлены" else "Обновление подписок с ошибками"
            val message = if (failures == 0) {
                "Обновлено подписок: $updated · серверов: $serverCount"
            } else {
                "Успешно: $updated из ${targets.size} · ошибок: $failures"
            }
            postRefreshNotification(applicationContext, title, message, failures > 0)
            // A partial refresh is not fully successful: ask WorkManager to
            // retry with backoff so temporarily unavailable providers are retried
            // instead of waiting for the next periodic interval.
            if (failures > 0) Result.retry() else Result.success()
        } catch (error: Exception) {
            postRefreshNotification(applicationContext, "Не удалось обновить подписки", error.message ?: "Проверь подключение к интернету", true)
            Result.retry()
        }
    }
}

private fun postRefreshNotification(context: Context, title: String, message: String, warning: Boolean) {
    try {
        val settings = context.getSharedPreferences("astra_settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean("subscription_notifications", true)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "astra_subscription_updates"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Обновления подписок", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Результаты автоматического обновления VPN-подписок"
            }
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channelId)
            else Notification.Builder(context)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(if (warning) Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT)
            .build()
        manager.notify(if (warning) 4202 else 4201, notification)
    } catch (_: Exception) {
        // Notifications are optional; subscription refresh must still complete.
    }
}

internal object SubscriptionRefreshInterval {
    /** Keep periodic work within Android WorkManager's supported 1–24 hour product range. */
    fun normalize(hours: Int): Int = hours.coerceIn(1, 24)
}

internal object SubscriptionRefreshTargets {
    /** Retry failed sources and include subscriptions added since the previous run. */
    fun select(sources: List<String>, pending: Set<String>, previouslyKnown: Set<String> = sources.toSet()): List<String> {
        if (sources.isEmpty()) return emptyList()
        val targets = sources.filter { it in pending || it !in previouslyKnown }
        return targets.ifEmpty { sources.filter { it in pending }.ifEmpty { sources } }
    }
}

object SubscriptionRefreshScheduler {
    private const val UNIQUE_WORK = "astra_subscription_refresh"

    fun sync(context: Context) {
        val settings = context.getSharedPreferences("astra_settings", Context.MODE_PRIVATE)
        val manager = WorkManager.getInstance(context)
        if (!settings.getBoolean("auto_update", false)) {
            manager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val hours = SubscriptionRefreshInterval.normalize(settings.getInt("update_interval_hours", 2))
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
