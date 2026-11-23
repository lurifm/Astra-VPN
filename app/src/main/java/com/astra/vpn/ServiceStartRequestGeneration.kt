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

    /** Restores the persisted high-water mark after Android recreates the app process. */
    @Synchronized
    fun restoreAtLeast(persistedGeneration: Long) {
        if (persistedGeneration >= 0L && persistedGeneration > generation) {
            generation = persistedGeneration
        }
    }

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = generation == candidate
}
