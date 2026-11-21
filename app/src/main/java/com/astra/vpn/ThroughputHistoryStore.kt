package com.astra.vpn

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Stores non-secret throughput measurements separately from encrypted profile
 * links. Profile URIs are represented only by SHA-256 keys, never persisted here.
 */
internal class ThroughputHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun put(profile: String, sample: ThroughputSample) {
        require(profile.isNotBlank())
        require(ThroughputFreshnessPolicy.isFresh(sample, System.currentTimeMillis())) {
            "Некорректный или устаревший результат измерения скорости"
        }
        val value = JSONObject()
            .put("downloadMbps", sample.downloadMbps)
            .put("uploadMbps", sample.uploadMbps)
            .put("measuredAt", sample.measuredAtEpochMs)
        // apply() updates the in-memory cache immediately and persists on a
        // background thread; this method is also called from BroadcastReceiver
        // callbacks on the main thread, so commit() would risk a UI stall.
        prefs.edit().putString(key(profile), value.toString()).apply()
        prune()
    }

    @Synchronized
    fun get(profile: String, nowEpochMs: Long = System.currentTimeMillis()): ThroughputSample? {
        val storageKey = key(profile)
        val raw = prefs.getString(storageKey, null) ?: return null
        val sample = runCatching {
            val json = JSONObject(raw)
            ThroughputSample(
                json.getDouble("downloadMbps"),
                json.getDouble("uploadMbps"),
                json.getLong("measuredAt")
            )
        }.getOrNull()
        if (sample == null || !ThroughputFreshnessPolicy.isFresh(sample, nowEpochMs)) {
            // Remove corrupt/expired entries on read so repeated profile-list
            // renders do not keep encountering the same unusable cache value.
            prefs.edit().remove(storageKey).apply()
            return null
        }
        return sample
    }

    @Synchronized
    fun getFresh(profiles: Collection<String>, nowEpochMs: Long = System.currentTimeMillis()): Map<String, ThroughputSample> =
        profiles.distinct().mapNotNull { profile ->
            get(profile, nowEpochMs)?.let { profile to it }
        }.toMap()

    @Synchronized
    fun remove(profile: String) {
        prefs.edit().remove(key(profile)).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        prefs.all.forEach { (storedKey, raw) ->
            val sample = (raw as? String)?.let {
                runCatching {
                    val json = JSONObject(it)
                    ThroughputSample(
                        json.getDouble("downloadMbps"),
                        json.getDouble("uploadMbps"),
                        json.getLong("measuredAt")
                    )
                }.getOrNull()
            }
            if (sample == null || !ThroughputFreshnessPolicy.isFresh(sample, now)) {
                editor.remove(storedKey)
            }
        }
        // SharedPreferences applies removals to its in-memory map immediately;
        // disk I/O is delegated away from the caller/UI thread.
        editor.apply()
    }

    private fun key(profile: String): String =
        PREFIX + MessageDigest.getInstance("SHA-256")
            .digest(profile.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFERENCES = "astra_throughput_history"
        private const val PREFIX = "sample_"
    }
}
