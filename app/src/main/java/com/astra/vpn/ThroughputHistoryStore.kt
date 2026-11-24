package com.astra.vpn

import android.content.Context
import org.json.JSONObject

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
        require(sample.networkHandle > 0L && ThroughputFreshnessPolicy.isFresh(sample, System.currentTimeMillis())) {
            "Некорректный или устаревший результат измерения скорости"
        }
        val value = JSONObject()
            .put("downloadMbps", sample.downloadMbps)
            .put("uploadMbps", sample.uploadMbps)
            .put("measuredAt", sample.measuredAtEpochMs)
            .put("networkHandle", sample.networkHandle)
        // apply() updates the in-memory cache immediately and persists on a
        // background thread; this method is also called from BroadcastReceiver
        // callbacks on the main thread, so commit() would risk a UI stall.
        ensureCacheLoaded(System.currentTimeMillis())
        // Keep a separate fresh sample for each physical underlay.
        val storageKey = ThroughputHistoryKey.sampleKey(profile, sample.networkHandle)
        sampleCache[storageKey] = sample
        prefs.edit().putString(storageKey, value.toString()).apply()
        prune(System.currentTimeMillis())
    }

    @Synchronized
    fun get(
        profile: String,
        expectedNetworkHandle: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ThroughputSample? {
        if (expectedNetworkHandle <= 0L) return null
        ensureCacheLoaded(nowEpochMs)
        val storageKey = ThroughputHistoryKey.sampleKey(profile, expectedNetworkHandle)
        val sample = sampleCache[storageKey] ?: return null
        if (!ThroughputFreshnessPolicy.isFresh(sample, nowEpochMs, expectedNetworkHandle)) {
            sampleCache.remove(storageKey)
            prefs.edit().remove(storageKey).apply()
            return null
        }
        return sample
    }

    @Synchronized
    fun getFresh(
        profiles: Collection<String>,
        expectedNetworkHandle: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Map<String, ThroughputSample> =
        profiles.distinct().mapNotNull { profile ->
            get(profile, expectedNetworkHandle, nowEpochMs)?.let { profile to it }
        }.toMap()

    @Synchronized
    fun remove(profile: String) {
        ensureCacheLoaded(System.currentTimeMillis())
        val legacyKey = ThroughputHistoryKey.legacyKey(profile)
        val matchingKeys = prefs.all.keys.filter {
            it == legacyKey || it.startsWith(legacyKey + "_")
        }
        matchingKeys.forEach(sampleCache::remove)
        val editor = prefs.edit()
        matchingKeys.forEach(editor::remove)
        editor.apply()
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
                        json.getLong("measuredAt"),
                        json.getLong("networkHandle")
                    )
                }.getOrNull()
            }
            val legacyKey = ThroughputHistoryKey.isLegacyKey(storedKey)
            val validCurrentKey = sample != null &&
                ThroughputHistoryKey.isSampleKey(storedKey, sample.networkHandle)
            if (sample != null && ThroughputFreshnessPolicy.isFresh(sample, nowEpochMs) &&
                (legacyKey || validCurrentKey)
            ) {
                if (legacyKey) {
                    // Migrate the previous single-underlay format in place.
                    val migratedKey = ThroughputHistoryKey.sampleKeyFromLegacy(storedKey, sample.networkHandle)
                    sampleCache[migratedKey] = sample
                    editor.putString(migratedKey, raw as String)
                    editor.remove(storedKey)
                } else {
                    sampleCache[storedKey] = sample
                }
            } else {
                editor.remove(storedKey)
            }
        }
        cacheLoaded = true
        editor.apply()
        // Enforce the same storage bound when restoring an older oversized cache.
        prune(nowEpochMs)
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

    companion object {
        private const val PREFERENCES = "astra_throughput_history"
        private const val PREFIX = "sample_"
        private const val MAX_SAMPLES = 500
    }
}
