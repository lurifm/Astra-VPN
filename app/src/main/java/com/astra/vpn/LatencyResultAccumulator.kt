package com.astra.vpn

/**
 * Thread-safe accumulator for URL-test measurements.
 *
 * Native callbacks may report zero/null placeholders before a real measurement.
 * Those placeholders must never erase a previously observed positive latency.
 */
internal class LatencyResultAccumulator(expectedTags: Set<String>) {
    private val results = linkedMapOf<String, Long?>().apply {
        expectedTags.forEach { put(it, null) }
    }

    @Synchronized
    fun merge(snapshot: Map<String, Long?>): Map<String, Long?> {
        snapshot.forEach { (tag, delay) ->
            if (tag in results && delay != null && delay > 0L) {
                results[tag] = delay
            }
        }
        return results.toMap()
    }

    @Synchronized
    fun snapshot(): Map<String, Long?> = results.toMap()

    @Synchronized
    fun allMeasured(): Boolean =
        results.isNotEmpty() && results.values.all { it != null && it > 0L }
}
