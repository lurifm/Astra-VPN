package com.astra.vpn

/**
 * Invalidates asynchronous start/reload completions when a newer start or stop
 * supersedes the request that initiated them.
 */
internal class ServiceStartRequestGeneration {
    private var generation = 0L

    @Synchronized
    fun next(): Long {
        generation++
        return generation
    }

    @Synchronized
    fun invalidate() {
        generation++
    }

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = generation == candidate
}
