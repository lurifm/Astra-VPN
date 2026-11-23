package com.astra.vpn

/**
 * Filters delayed VPN service state broadcasts after a newer start/stop request
 * has already been observed by the Activity.
 */
internal object ServiceStateEventPolicy {
    fun shouldAccept(eventGeneration: Long, latestGeneration: Long?): Boolean {
        if (eventGeneration < 0L) return false
        return latestGeneration == null || eventGeneration >= latestGeneration
    }
}
