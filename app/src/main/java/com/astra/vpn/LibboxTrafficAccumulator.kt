package com.astra.vpn

/**
 * Accumulates non-negative traffic deltas reported by sing-box connection events.
 *
 * Values are grouped by outbound tag so callers can distinguish proxy outbounds
 * from direct/block traffic. This is not, by itself, a complete TUN byte counter:
 * coverage depends on which connection events libbox emits.
 */
internal class LibboxTrafficAccumulator {
    data class Totals(val uploadBytes: Long, val downloadBytes: Long)

    private val totalsByOutbound = linkedMapOf<String, MutableTotals>()

    @Synchronized
    fun add(outboundTag: String?, uploadDelta: Long, downloadDelta: Long) {
        val tag = outboundTag?.trim().orEmpty()
        if (tag.isEmpty()) return
        if (uploadDelta < 0L && downloadDelta < 0L) return

        val totals = totalsByOutbound.getOrPut(tag) { MutableTotals() }
        totals.upload = saturatingAdd(totals.upload, uploadDelta.coerceAtLeast(0L))
        totals.download = saturatingAdd(totals.download, downloadDelta.coerceAtLeast(0L))
    }

    @Synchronized
    fun snapshot(): Map<String, Totals> = totalsByOutbound.mapValues { (_, value) ->
        Totals(value.upload, value.download)
    }

    @Synchronized
    fun reset() {
        totalsByOutbound.clear()
    }

    private fun saturatingAdd(current: Long, delta: Long): Long =
        if (Long.MAX_VALUE - current < delta) Long.MAX_VALUE else current + delta

    private class MutableTotals(var upload: Long = 0L, var download: Long = 0L)
}
