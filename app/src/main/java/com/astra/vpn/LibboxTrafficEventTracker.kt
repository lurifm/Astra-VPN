package com.astra.vpn

/**
 * Maps libbox connection IDs to outbound tags and applies update-event deltas.
 * New/closed event totals are deliberately not added: only Update deltas are
 * traffic increments, which avoids double-counting cumulative connection totals.
 */
internal class LibboxTrafficEventTracker {
    private val outboundByConnection = mutableMapOf<String, String>()
    private val accumulator = LibboxTrafficAccumulator()

    @Synchronized
    fun onNew(connectionId: String?, outboundTag: String?) {
        val id = connectionId?.takeIf { it.isNotBlank() } ?: return
        val tag = outboundTag?.trim()?.takeIf { it.isNotEmpty() } ?: return
        outboundByConnection[id] = tag
    }

    @Synchronized
    fun onUpdate(connectionId: String?, outboundTag: String?, uploadDelta: Long, downloadDelta: Long) {
        val id = connectionId?.takeIf { it.isNotBlank() } ?: return
        val eventTag = outboundTag?.trim()?.takeIf { it.isNotEmpty() }
        val tag = eventTag ?: outboundByConnection[id] ?: return
        if (eventTag != null) outboundByConnection[id] = eventTag
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
}
