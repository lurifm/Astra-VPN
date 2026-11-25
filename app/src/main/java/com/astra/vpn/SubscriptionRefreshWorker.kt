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
                // An empty snapshot must not clear failures recorded by a concurrent import.
                return Result.success()
            }
            // Retry only providers that failed previously. Re-downloading successful
            // subscriptions on every WorkManager backoff wastes bandwidth and can
            // unnecessarily hit provider rate limits.
            // Subscription source URLs contain credentials. Persist only SHA-256
            // fingerprints in preferences, never the original URLs. Normalize legacy
            // entries once so upgrades also remove previously stored plaintext URLs.
            SubscriptionRefreshRetryState.updateKnownSources(settings, sources)
            val pending = settings.getStringSet("subscription_retry_sources", emptySet())
                .orEmpty().map { SubscriptionSourceFingerprint.fromStoredValue(it) }.toSet()
            val known = settings.getStringSet("subscription_known_sources", emptySet())
                .orEmpty().map(SubscriptionSourceFingerprint::fromStoredValue).toSet()
            val targets = SubscriptionRefreshTargets.selectSources(
                sources,
                pending,
                known
            )
            if (targets.isEmpty()) return Result.success()
            settings.edit().putLong("last_subscription_attempt", System.currentTimeMillis()).apply()
            var updated = 0
            var serverCount = 0
            var failures = 0
            val failedSources = linkedSetOf<String>()
            // One monotonic deadline spans the worker batch; each source retains its 2 MiB cap.
            val downloadBudget = SubscriptionDownloadBudget.forSubscriptionCount(targets.size)
            for (index in targets.indices) {
                val source = targets[index]
                // A stopped worker must not silently lose subscriptions it had not
                // reached yet. Persist those fingerprints so the next run resumes them.
                if (isStopped) {
                    failedSources += SubscriptionRefreshRetryState.pendingAfterStop(targets, index, failedSources)
                    SubscriptionRefreshRetryState.mergePending(settings, failedSources)
                    return Result.retry()
                }
                try {
                    val count = SubscriptionRefreshCoordinator.refreshExisting(
                        store,
                        source,
                        onAttempt = { SubscriptionRefreshHistory.recordAttempt(settings, source) },
                        onResult = { refreshResult ->
                            SubscriptionRefreshBookkeeping.recordResult(
                                refreshResult,
                                recordRetryResult = { succeeded ->
                                    SubscriptionRefreshRetryState.recordResult(settings, source, succeeded)
                                },
                                recordHistorySuccess = { SubscriptionRefreshHistory.recordSuccess(settings, source) },
                                recordHistoryFailure = { SubscriptionRefreshHistory.recordFailure(settings, source) }
                            )
                        },
                        downloadBudget = downloadBudget
                    )
                    if (count == null) continue
                    updated++
                    serverCount += count
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    targets.drop(index).forEach { failedSources += SubscriptionSourceFingerprint.of(it) }
                    SubscriptionRefreshRetryState.mergePending(settings, failedSources)
                    return Result.retry()
                } catch (_: java.util.concurrent.CancellationException) {
                    targets.drop(index).forEach { failedSources += SubscriptionSourceFingerprint.of(it) }
                    SubscriptionRefreshRetryState.mergePending(settings, failedSources)
                    return Result.retry()
                } catch (_: Exception) {
                    failures++
                    failedSources += SubscriptionSourceFingerprint.of(source)
                }
            }
            // Merge ordinary failures too; the early-return paths above already
            // persist their pending set before WorkManager retries.
            if (failedSources.isNotEmpty()) {
                SubscriptionRefreshRetryState.mergePending(settings, failedSources)
            }
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

/** Build the retry set when a worker is stopped before finishing its target list. */
internal object SubscriptionRefreshRetryState {
    // WorkManager and foreground refreshes share this process-wide read/modify/write lock.
    private val lock = Any()

    internal fun updatePendingAtomically(
        read: () -> Set<String>,
        write: (Set<String>) -> Unit,
        transform: (MutableSet<String>) -> Unit
    ) = synchronized(lock) {
        val pending = read().map(SubscriptionSourceFingerprint::fromStoredValue).toMutableSet()
        transform(pending)
        write(pending.toSet())
    }

    private fun updatePreferences(
        settings: android.content.SharedPreferences,
        transform: (MutableSet<String>) -> Unit
    ) = updatePendingAtomically(
        read = { settings.getStringSet("subscription_retry_sources", emptySet()).orEmpty() },
        write = { pending ->
            settings.edit().apply {
                if (pending.isEmpty()) remove("subscription_retry_sources")
                else putStringSet("subscription_retry_sources", pending)
            }.apply()
        },
        transform = transform
    )

    /** Update only this provider; preserve every other pending failure. */
    fun recordResult(
        settings: android.content.SharedPreferences,
        source: String,
        succeeded: Boolean
    ) = updatePreferences(settings) { pending ->
        applyResult(pending, source, succeeded)
    }

    internal fun afterSingleResult(
        currentPending: Set<String>,
        source: String,
        succeeded: Boolean
    ): Set<String> = currentPending.map(SubscriptionSourceFingerprint::fromStoredValue)
        .toMutableSet().also { applyResult(it, source, succeeded) }.toSet()

    private fun applyResult(pending: MutableSet<String>, source: String, succeeded: Boolean) {
        val fingerprint = SubscriptionSourceFingerprint.of(source)
        if (succeeded) pending.remove(fingerprint) else pending.add(fingerprint)
    }

    /** Merge interrupted/unprocessed targets without erasing concurrent failures. */
    fun mergePending(
        settings: android.content.SharedPreferences,
        additions: Set<String>
    ) = updatePreferences(settings) { it.addAll(additions) }

    /** Update known sources without pruning pending failures from newer imports. */
    fun updateKnownSources(
        settings: android.content.SharedPreferences,
        currentSources: List<String>
    ) = updateKnownSourcesAtomically(
        readPending = { settings.getStringSet("subscription_retry_sources", emptySet()).orEmpty() },
        writePending = { pending ->
            settings.edit().apply {
                if (pending.isEmpty()) remove("subscription_retry_sources")
                else putStringSet("subscription_retry_sources", pending)
            }.apply()
        },
        writeKnown = { known -> settings.edit().putStringSet("subscription_known_sources", known).apply() },
        currentSources = currentSources
    )

    internal fun updateKnownSourcesAtomically(
        readPending: () -> Set<String>,
        writePending: (Set<String>) -> Unit,
        writeKnown: (Set<String>) -> Unit,
        currentSources: List<String>
    ) = synchronized(lock) {
        val pending = readPending().map(SubscriptionSourceFingerprint::fromStoredValue).toSet()
        writePending(pending)
        writeKnown(currentSources.map(SubscriptionSourceFingerprint::of).toSet())
    }

    fun pendingAfterStop(
        targets: List<String>,
        nextIndex: Int,
        alreadyFailed: Set<String>
    ): Set<String> = buildSet {
        addAll(alreadyFailed.map(SubscriptionSourceFingerprint::fromStoredValue))
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
        val hours = SubscriptionRefreshInterval.normalize(settings.getInt("update_interval_hours", 2))
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
