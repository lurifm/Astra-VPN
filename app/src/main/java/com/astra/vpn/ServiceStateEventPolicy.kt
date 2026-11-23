package com.astra.vpn

/**
 * Filters delayed or reordered VPN state broadcasts.
 * Generations order distinct start/stop requests; sequences order events within one request.
 */
internal object ServiceStateEventPolicy {
    fun shouldAccept(
        eventGeneration: Long,
        eventSequence: Long,
        latestGeneration: Long?,
        latestSequence: Long?
    ): Boolean {
        if (eventGeneration < 0L || eventSequence < 0L) return false
        if (latestGeneration == null || eventGeneration > latestGeneration) return true
        if (eventGeneration < latestGeneration) return false
        return latestSequence == null || eventSequence > latestSequence
    }
}
