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
import java.security.MessageDigest
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
            // Subscription source URLs contain credentials. Persist only SHA-256
            // fingerprints in preferences, never the original URLs. Normalize legacy
            // entries once so upgrades also remove previously stored plaintext URLs.
            val pending = settings.getStringSet("subscription_retry_sources", emptySet())
                .orEmpty().map { SubscriptionSourceFingerprint.fromStoredValue(it) }.toSet()
            val known = settings.getStringSet("subscription_known_sources", emptySet())
                .orEmpty().map(SubscriptionSourceFingerprint::fromStoredValue).toSet()
            val summaries = store.subscriptionSummaries().associateBy { it.sourceUrl }
            val defaultIntervalHours = SubscriptionRefreshInterval.normalize(
                settings.getInt("update_interval_hours", 2)
            )
            val now = System.currentTimeMillis()
            val candidates = sources.map { source ->
                val summary = summaries[source]
                val history = SubscriptionRefreshHistory.get(settings, source, now)
                SubscriptionRefreshCandidate(
                    fingerprint = SubscriptionSourceFingerprint.of(source),
                    intervalHours = summary?.updateIntervalHours ?: defaultIntervalHours,
                    lastSuccessAt = history?.takeIf { it.state == "success" }?.timestamp
                )
            }
            val sourceByFingerprint = sources.associateBy(SubscriptionSourceFingerprint::of)
            val targets = SubscriptionRefreshDuePolicy.select(
                candidates = candidates,
                pendingFingerprints = pending,
                knownFingerprints = known,
                defaultIntervalHours = defaultIntervalHours,
                now = now
            ).mapNotNull(sourceByFingerprint::get)
            if (targets.isEmpty()) {
                // Persist normalized fingerprints even while every provider is inside its refresh window.
                SubscriptionRefreshRetryState.persist(settings, emptySet(), sources)
                return Result.success()
            }
            settings.edit().putLong("last_subscription_attempt", System.currentTimeMillis()).apply()
            var updated = 0
            var serverCount = 0
            var failures = 0
            val failedSources = linkedSetOf<String>()
            for (index in targets.indices) {
                val source = targets[index]
                // A stopped worker must not silently lose subscriptions it had not
                // reached yet. Persist those fingerprints so the next run resumes them.
                if (isStopped) {
                    failedSources += SubscriptionRefreshRetryState.pendingAfterStop(targets, index, failedSources)
                    SubscriptionRefreshRetryState.persist(settings, failedSources, sources)
                    return Result.retry()
                }
                SubscriptionRefreshHistory.recordAttempt(settings, source)
                try {
                    val count = SubscriptionRefreshCoordinator.refresh(store, source)
                    SubscriptionRefreshHistory.recordSuccess(settings, source)
                    updated++
                    serverCount += count
                } catch (_: InterruptedException) {
                    SubscriptionRefreshHistory.recordFailure(settings, source)
                    Thread.currentThread().interrupt()
                    targets.drop(index).forEach { failedSources += SubscriptionSourceFingerprint.of(it) }
                    SubscriptionRefreshRetryState.persist(settings, failedSources, sources)
                    return Result.retry()
                } catch (_: java.util.concurrent.CancellationException) {
                    SubscriptionRefreshHistory.recordFailure(settings, source)
                    targets.drop(index).forEach { failedSources += SubscriptionSourceFingerprint.of(it) }
                    SubscriptionRefreshRetryState.persist(settings, failedSources, sources)
                    return Result.retry()
                } catch (_: Exception) {
                    SubscriptionRefreshHistory.recordFailure(settings, source)
                    failures++
                    failedSources += SubscriptionSourceFingerprint.of(source)
                }
            }
            SubscriptionRefreshRetryState.persist(settings, failedSources, sources)
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
        } catch (_: Exception) {
            // Exception messages may contain full subscription URLs, including credentials.
            // Keep notification text generic and never expose source secrets on the lock screen.
            postRefreshNotification(applicationContext, "Не удалось обновить подписки", "Проверь подключение к интернету и настройки подписок.", true)
            Result.retry()
        }
    }
}

internal data class SubscriptionRefreshCandidate(
    val fingerprint: String,
    val intervalHours: Int,
    val lastSuccessAt: Long?
)

/** Select only providers whose effective refresh window has elapsed. */
internal object SubscriptionRefreshDuePolicy {
    fun select(
        candidates: List<SubscriptionRefreshCandidate>,
        pendingFingerprints: Set<String>,
        knownFingerprints: Set<String>,
        defaultIntervalHours: Int,
        now: Long
    ): List<String> {
        val fallbackHours = SubscriptionRefreshInterval.normalize(defaultIntervalHours)
        return candidates.filter { candidate ->
            when {
                candidate.fingerprint in pendingFingerprints -> true
                candidate.fingerprint !in knownFingerprints -> true
                candidate.lastSuccessAt == null || candidate.lastSuccessAt <= 0L -> true
                now < candidate.lastSuccessAt -> true
                else -> {
                    // Never poll more frequently than either the user's minimum or provider advice.
                    val effectiveHours = maxOf(
                        fallbackHours,
                        candidate.intervalHours.coerceIn(1, 720)
                    )
                    now - candidate.lastSuccessAt >= effectiveHours.toLong() * 60L * 60L * 1000L
                }
            }
        }.map { it.fingerprint }
    }
}

/** Build the retry set when a worker is stopped before finishing its target list. */
internal object SubscriptionRefreshRetryState {
    fun persist(
        settings: android.content.SharedPreferences,
        failedSources: Set<String>,
        currentSources: List<String>
    ) {
        settings.edit().apply {
            if (failedSources.isEmpty()) remove("subscription_retry_sources")
            else putStringSet("subscription_retry_sources", failedSources.toSet())
            putStringSet("subscription_known_sources", currentSources.map(SubscriptionSourceFingerprint::of).toSet())
        }.apply()
    }

    fun pendingAfterStop(
        targets: List<String>,
        nextIndex: Int,
        alreadyFailed: Set<String>
    ): Set<String> = buildSet {
        addAll(alreadyFailed)
        targets.drop(nextIndex.coerceIn(0, targets.size))
            .mapTo(this, SubscriptionSourceFingerprint::of)
    }
}

/**
 * Stable non-reversible identifiers for subscription sources stored in preferences.
 * Legacy plaintext entries are converted on read so the next successful write migrates them.
 */
internal object SubscriptionSourceFingerprint {
    fun fromStoredValue(value: String): String =
        if (value.matches(Regex("[0-9a-f]{64}"))) value else of(value)

    fun of(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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

    /** Milliseconds used by the foreground refresh gate; keep it aligned with WorkManager. */
    fun intervalMillis(hours: Int): Long = normalize(hours).toLong() * 60L * 60L * 1000L
}

internal object SubscriptionRefreshTargets {
    /**
     * Preferences store fingerprints, while ProfileStore returns source URLs.
     * Compare identifiers in the same representation, then return original URLs
     * to the downloader without persisting or exposing them in retry state.
     */
    fun selectSources(
        sources: List<String>,
        pendingFingerprints: Set<String>,
        knownFingerprints: Set<String>
    ): List<String> {
        val fingerprints = sources.map(SubscriptionSourceFingerprint::of)
        val selected = select(fingerprints, pendingFingerprints, knownFingerprints)
        val sourceByFingerprint = sources.associateBy(SubscriptionSourceFingerprint::of)
        return selected.mapNotNull(sourceByFingerprint::get)
    }

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
        // Run a lightweight due-check hourly; downloads obey each source's effective interval.
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
