package com.astra.vpn

/**
 * Monotonic token used to invalidate callbacks owned by an older service instance.
 */
internal class ServiceLifecycleGeneration {
    private var value = 0L

    @Synchronized
    fun advance(): Long {
        value += 1L
        return value
    }

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = value == candidate
}
