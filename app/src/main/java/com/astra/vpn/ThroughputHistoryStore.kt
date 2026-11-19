package com.astra.vpn

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Keeps a bounded local history of successful throughput probes.
 * Raw profile links are never stored; each key is a SHA-256 digest.
 */
internal class ThroughputHistoryStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun record(profile: String, downloadMbps: Double, now: Long = System.currentTimeMillis()) {
        if (profile.isBlank() || now <= 0L || !downloadMbps.isFinite() ||
            downloadMbps !in MIN_VALID_MBPS..MAX_VALID_MBPS
        ) return
        synchronized(LOCK) {
            val key = storageKey(profile)
            val existing = read(key, now).toMutableList()
            val latest = existing.lastOrNull()
            if (latest != null &&
                (now <= latest.timestampMs || now - latest.timestampMs < MIN_SAMPLE_INTERVAL_MS)
            ) return
            existing += ThroughputStabilityScorer.Sample(now, downloadMbps)
            val json = JSONArray()
            existing.takeLast(MAX_SAMPLES).forEach { sample ->
                json.put(
                    JSONObject()
                        .put("at", sample.timestampMs)
                        .put("mbps", sample.downloadMbps)
                )
            }
            prefs.edit().putString(key, json.toString()).apply()
        }
    }

    fun samples(profile: String, now: Long = System.currentTimeMillis()): List<ThroughputStabilityScorer.Sample> {
        if (profile.isBlank() || now <= 0L) return emptyList()
        return synchronized(LOCK) { read(storageKey(profile), now) }
    }

    fun medianMbps(profile: String, now: Long = System.currentTimeMillis()): Double? =
        ThroughputStabilityScorer.medianMbps(samples(profile, now), now)

    private fun read(key: String, now: Long): List<ThroughputStabilityScorer.Sample> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val timestamp = item.optLong("at", -1L)
                val speed = item.optDouble("mbps", Double.NaN)
                if (timestamp <= 0L || timestamp > now || now - timestamp > HISTORY_WINDOW_MS) continue
                if (!speed.isFinite() || speed !in MIN_VALID_MBPS..MAX_VALID_MBPS) continue
                add(ThroughputStabilityScorer.Sample(timestamp, speed))
            }
        }.sortedBy { it.timestampMs }.takeLast(MAX_SAMPLES)
    }

    private fun storageKey(profile: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(profile.toByteArray(Charsets.UTF_8))
        val suffix = digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "profile_$suffix"
    }

    private companion object {
        const val PREFS_NAME = "astra_throughput_history"
        const val MAX_SAMPLES = 12
        const val MIN_SAMPLE_INTERVAL_MS = 60_000L
        const val HISTORY_WINDOW_MS = ThroughputStabilityScorer.DEFAULT_WINDOW_MS
        const val MIN_VALID_MBPS = 0.01
        const val MAX_VALID_MBPS = 100_000.0
        val LOCK = Any()
    }
}
