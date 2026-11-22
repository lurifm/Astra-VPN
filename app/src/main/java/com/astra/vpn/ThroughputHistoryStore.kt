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
    // Decode persisted JSON and hash each profile only once per process. Server-list
    // renders can happen repeatedly while Ping updates arrive; they should be memory-only.
    private val sampleCache = HashMap<String, ThroughputSample>()
    private var cacheLoaded = false

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
        ensureCacheLoaded(System.currentTimeMillis())
        val storageKey = key(profile)
        sampleCache[storageKey] = sample
        prefs.edit().putString(storageKey, value.toString()).apply()
        prune(System.currentTimeMillis())
    }

    @Synchronized
    fun get(profile: String, nowEpochMs: Long = System.currentTimeMillis()): ThroughputSample? {
        ensureCacheLoaded(nowEpochMs)
        val storageKey = key(profile)
        val sample = sampleCache[storageKey] ?: return null
        if (!ThroughputFreshnessPolicy.isFresh(sample, nowEpochMs)) {
            sampleCache.remove(storageKey)
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
        val storageKey = key(profile)
        sampleCache.remove(storageKey)
        prefs.edit().remove(storageKey).apply()
    }

    @Synchronized
    fun clear() {
        sampleCache.clear()
        cacheLoaded = true
        prefs.edit().clear().apply()
    }

    /** Load and validate the persisted snapshot once; later reads never parse JSON. */
    private fun ensureCacheLoaded(nowEpochMs: Long) {
        if (cacheLoaded) return
        val editor = prefs.edit()
        prefs.all.forEach { (storedKey, raw) ->
            if (!storedKey.startsWith(PREFIX)) return@forEach
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
            if (sample != null && ThroughputFreshnessPolicy.isFresh(sample, nowEpochMs)) {
                sampleCache[storedKey] = sample
            } else {
                editor.remove(storedKey)
            }
        }
        cacheLoaded = true
        editor.apply()
    }

    private fun prune(nowEpochMs: Long) {
        val expiredKeys = sampleCache.filterValues {
            !ThroughputFreshnessPolicy.isFresh(it, nowEpochMs)
        }.keys.toMutableSet()

        // A user may import a very large subscription. Keep this cache bounded
        // even if every server is measured within the five-minute freshness
        // window; retain the most recently measured profiles deterministically.
        val remainingCount = sampleCache.size - expiredKeys.size
        if (remainingCount > MAX_SAMPLES) {
            val excess = remainingCount - MAX_SAMPLES
            sampleCache.entries
                .asSequence()
                .filter { it.key !in expiredKeys }
                .sortedWith(compareBy<Map.Entry<String, ThroughputSample>> { it.value.measuredAtEpochMs }
                    .thenBy { it.key })
                .take(excess)
                .forEach { expiredKeys.add(it.key) }
        }

        if (expiredKeys.isEmpty()) return
        expiredKeys.forEach(sampleCache::remove)
        val editor = prefs.edit()
        expiredKeys.forEach(editor::remove)
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
        private const val MAX_SAMPLES = 500
    }
}
