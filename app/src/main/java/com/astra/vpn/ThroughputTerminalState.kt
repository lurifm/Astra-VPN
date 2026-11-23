package com.astra.vpn

import java.util.concurrent.atomic.AtomicInteger

/**
 * Guarantees that exactly one terminal outcome wins for a throughput probe.
 * Cancellation only wins while the probe is still active.
 */
internal class ThroughputTerminalState {
    private val state = AtomicInteger(ACTIVE)

    fun claimSuccess(): Boolean = state.compareAndSet(ACTIVE, SUCCESS)
    fun claimFailure(): Boolean = state.compareAndSet(ACTIVE, FAILURE)
    fun cancel(): Boolean = state.compareAndSet(ACTIVE, CANCELLED)

    internal companion object {
        private const val ACTIVE = 0
        private const val SUCCESS = 1
        private const val FAILURE = 2
        private const val CANCELLED = 3
    }
}
