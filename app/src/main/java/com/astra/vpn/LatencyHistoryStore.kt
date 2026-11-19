package com.astra.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Stores a small history of successful URL-test latency samples.
 * Profile URIs are never persisted: each profile is represented by a SHA-256 key.
 */
internal class LatencyHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("astra_latency_history", Context.MODE_PRIVATE)

    fun record(profile: String, latencyMs: Long, now: Long = System.currentTimeMillis()) {
        if (profile.isBlank() || latencyMs !in 1L..MAX_VALID_LATENCY_MS || now <= 0L) return
        val key = storageKey(profile)
        val existing = read(key, now).toMutableList()
        val latest = existing.lastOrNull()
        // A completed result may be restored after Activity recreation. Avoid
        // treating that same test as a second independent stability sample.
        if (latest != null && (now <= latest.timestampMs || now - latest.timestampMs < MIN_SAMPLE_INTERVAL_MS)) return
        existing += LatencyStabilityScorer.Sample(now, latencyMs)
        val bounded = existing.takeLast(MAX_SAMPLES)
        val json = JSONArray()
        bounded.forEach { sample ->
            json.put(
                JSONObject()
                    .put("at", sample.timestampMs)
                    .put("ms", sample.latencyMs)
            )
        }
        prefs.edit().putString(key, json.toString()).apply()
    }

    fun samples(profile: String, now: Long = System.currentTimeMillis()): List<LatencyStabilityScorer.Sample> {
        if (profile.isBlank() || now <= 0L) return emptyList()
        return read(storageKey(profile), now)
    }

    private fun read(key: String, now: Long): List<LatencyStabilityScorer.Sample> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val timestamp = item.optLong("at", -1L)
                val latency = item.optLong("ms", -1L)
                if (timestamp <= 0L || timestamp > now || now - timestamp > HISTORY_WINDOW_MS) continue
                if (latency !in 1L..MAX_VALID_LATENCY_MS) continue
                add(LatencyStabilityScorer.Sample(timestamp, latency))
            }
        }.sortedBy { it.timestampMs }.takeLast(MAX_SAMPLES)
    }

    private fun storageKey(profile: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(profile.toByteArray(Charsets.UTF_8))
        val suffix = digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "profile_$suffix"
    }

    private companion object {
        const val MAX_SAMPLES = 12
        const val MIN_SAMPLE_INTERVAL_MS = 60_000L
        const val HISTORY_WINDOW_MS = 21L * 24L * 60L * 60L * 1_000L
        const val MAX_VALID_LATENCY_MS = 60_000L
    }
}
