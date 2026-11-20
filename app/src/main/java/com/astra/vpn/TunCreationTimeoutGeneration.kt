package com.astra.vpn

/**
 * Identifies the currently active TUN-creation timeout.
 *
 * Unlike the service lifecycle generation, this token advances for every wait,
 * including repeated profile reloads handled by the same CommandServer.
 */
internal class TunCreationTimeoutGeneration {
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
