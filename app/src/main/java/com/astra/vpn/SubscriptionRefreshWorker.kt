package com.astra.vpn

import android.content.Context
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
            if (sources.isEmpty()) return Result.success()
            var failures = 0
            sources.forEach { source ->
                try {
                    val parsed = SubscriptionParser.importDetailed(source)
                    store.replaceSubscription(source, parsed.links, parsed.metadata)
                } catch (_: Exception) {
                    failures++
                }
            }
            settings.edit().putLong("last_subscription_update", System.currentTimeMillis()).apply()
            if (failures == sources.size) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
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
        val hours = settings.getInt("update_interval_hours", 2).coerceIn(1, 24)
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
