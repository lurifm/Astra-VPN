package com.astra.vpn

/** Monotonic ordering for state broadcasts, including across process recreation. */
internal class ServiceStateEventSequence {
    private var sequence = 0L

    @Synchronized
    fun next(): Long {
        check(sequence < Long.MAX_VALUE) { "VPN state event sequence exhausted" }
        sequence += 1L
        return sequence
    }

    @Synchronized
    fun restoreAtLeast(persistedSequence: Long) {
        if (persistedSequence >= 0L && persistedSequence > sequence) {
            sequence = persistedSequence
        }
    }

    @Synchronized
    fun current(): Long = sequence
}
