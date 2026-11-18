package com.astra.vpn

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * Reads Android's device-level per-UID network counters.
 *
 * These counters are supplied by Android and are not VPN-tunnel-only. The mobile
 * query can be unavailable on newer Android releases because subscriber IDs are
 * restricted; in that case Wi-Fi results are still returned and the limitation
 * is surfaced to the caller.
 */
internal object AppTrafficStatsReader {
    data class AppTraffic(
        val uid: Int,
        val label: String,
        val downloadedBytes: Long,
        val uploadedBytes: Long
    ) {
        val totalBytes: Long get() = downloadedBytes + uploadedBytes
    }

    data class DailySnapshot(
        val dayKey: Int,
        val dayLabel: String,
        val apps: List<AppTraffic>
    )

    data class Result(
        val apps: List<AppTraffic>,
        val mobileAvailable: Boolean,
        val wifiAvailable: Boolean,
        val startTime: Long,
        val endTime: Long,
        val totalDownloadedBytes: Long,
        val totalUploadedBytes: Long
    )

    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun readLastDay(context: Context, now: Long = System.currentTimeMillis()): Result =
        readPeriod(context, now - TimeUnit.DAYS.toMillis(1), now)

    /** Reads seven local calendar days; today's entry is partial up to the current time. */
    fun readLastSevenDays(context: Context, now: Long = System.currentTimeMillis()): List<DailySnapshot> {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, -6)
        }
        val dateFormat = java.text.SimpleDateFormat("EE", java.util.Locale.getDefault())
        val result = mutableListOf<DailySnapshot>()
        repeat(7) {
            val start = calendar.timeInMillis
            val dayKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(calendar.time).toInt()
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val end = minOf(calendar.timeInMillis, now)
            if (start < end) {
                val day = runCatching { readPeriod(context, start, end) }.getOrNull()
                if (day != null) result += DailySnapshot(dayKey, dateFormat.format(java.util.Date(start)), day.apps)
            } else {
                result += DailySnapshot(dayKey, dateFormat.format(start), emptyList())
            }
        }
        return result
    }

    fun readPeriod(context: Context, start: Long, end: Long): Result {
        check(hasAccess(context)) { "Для статистики приложений нужен доступ Usage Access" }
        require(start < end) { "Некорректный период статистики" }
        val now = end
        val totals = HashMap<Int, LongArray>()
        var wifiAvailable = false
        var mobileAvailable = false
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        fun collect(networkType: Int, mobile: Boolean) {
            var stats: NetworkStats? = null
            try {
                // A null subscriber ID requests the aggregate mobile template. Some
                // Android/OEM builds deny this query; never turn that into zero traffic.
                stats = manager.querySummary(networkType, null, start, now)
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    if (!stats.getNextBucket(bucket)) break
                    val uid = bucket.uid
                    if (uid < 0) continue
                    val values = totals.getOrPut(uid) { longArrayOf(0L, 0L) }
                    values[0] = (values[0] + bucket.rxBytes).coerceAtLeast(0L)
                    values[1] = (values[1] + bucket.txBytes).coerceAtLeast(0L)
                }
                if (mobile) mobileAvailable = true else wifiAvailable = true
            } finally {
                stats?.close()
            }
        }

        try { collect(ConnectivityManager.TYPE_WIFI, false) } catch (_: Exception) { }
        try { collect(ConnectivityManager.TYPE_MOBILE, true) } catch (_: Exception) { }

        val labels = HashMap<Int, String>()
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val launchable = pm.queryIntentActivities(launcherIntent, 0)
        for (activity in launchable) {
            val app = activity.activityInfo?.applicationInfo ?: continue
            val label = runCatching { pm.getApplicationLabel(app).toString() }
                .getOrDefault(app.packageName)
            val previous = labels[app.uid]
            labels[app.uid] = if (previous == null || label.length < previous.length) label else previous
        }
        val ownInfo = runCatching { pm.getApplicationInfo(context.packageName, 0) }.getOrNull()
        if (ownInfo != null) labels[ownInfo.uid] = pm.getApplicationLabel(ownInfo).toString()

        val apps = totals.mapNotNull { (uid, bytes) ->
            val total = bytes[0] + bytes[1]
            if (total <= 0L) null
            else AppTraffic(uid, labels[uid] ?: "Системное приложение ($uid)", bytes[0], bytes[1])
        }.sortedByDescending { it.totalBytes }

        return Result(
            apps, mobileAvailable, wifiAvailable, start, now,
            apps.sumOf { it.downloadedBytes }.coerceAtLeast(0L),
            apps.sumOf { it.uploadedBytes }.coerceAtLeast(0L)
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
    }
}
