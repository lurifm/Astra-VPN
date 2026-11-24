package com.astra.vpn

/**
 * Maps libbox connection IDs to outbound tags and applies update-event deltas.
 * New/closed event totals are deliberately not added: only Update deltas are
 * traffic increments, which avoids double-counting cumulative connection totals.
 */
internal class LibboxTrafficEventTracker {
    private val outboundByConnection = LinkedHashMap<String, String>()
    private val accumulator = LibboxTrafficAccumulator()

    @Synchronized
    fun onNew(connectionId: String?, outboundTag: String?) {
        val id = connectionId?.takeIf { it.isNotBlank() } ?: return
        // A new event may reuse an ID whose previous close event was lost.
        // Clear the previous attribution even when the new event has no usable tag.
        outboundByConnection.remove(id)
        val tag = outboundTag?.trim()?.takeIf { it.isNotEmpty() } ?: return
        outboundByConnection[id] = tag
        trimConnectionCache()
    }

    @Synchronized
    fun onUpdate(connectionId: String?, outboundTag: String?, uploadDelta: Long, downloadDelta: Long) {
        val id = connectionId?.takeIf { it.isNotBlank() } ?: return
        val eventTag = outboundTag?.trim()?.takeIf { it.isNotEmpty() }
        val tag = eventTag ?: outboundByConnection[id] ?: return
        if (eventTag != null) {
            outboundByConnection.remove(id)
            outboundByConnection[id] = eventTag
            trimConnectionCache()
        } else {
            // Refresh insertion order: the cache evicts least-recently-updated IDs.
            outboundByConnection[id]?.let {
                outboundByConnection.remove(id)
                outboundByConnection[id] = it
            }
        }
        accumulator.add(tag, uploadDelta, downloadDelta)
    }

    @Synchronized
    fun onClosed(connectionId: String?) {
        val id = connectionId?.takeIf { it.isNotBlank() } ?: return
        outboundByConnection.remove(id)
    }

    @Synchronized
    fun reset() {
        outboundByConnection.clear()
        accumulator.reset()
    }

    @Synchronized
    fun snapshot(): Map<String, LibboxTrafficAccumulator.Totals> = accumulator.snapshot()

    private fun trimConnectionCache() {
        while (outboundByConnection.size > MAX_TRACKED_CONNECTIONS) {
            val oldestId = outboundByConnection.entries.iterator().next().key
            outboundByConnection.remove(oldestId)
        }
    }

    private companion object {
        // Defensive ceiling in case a close/reset event is lost. Normal active
        // connection counts are far below this; eviction only affects attribution.
        const val MAX_TRACKED_CONNECTIONS = 4_096
    }
}
