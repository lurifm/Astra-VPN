package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ThroughputTerminalStateTest {
    @Test
    fun successFailureAndCancellationAreMutuallyExclusive() {
        val state = ThroughputTerminalState()
        assertTrue(state.claimFailure())
        assertFalse(state.claimSuccess())
        assertFalse(state.cancel())
        assertFalse(state.claimFailure())
    }

    @Test
    fun cancellationPreventsLaterTerminalEvents() {
        val state = ThroughputTerminalState()
        assertTrue(state.cancel())
        assertFalse(state.claimSuccess())
        assertFalse(state.claimFailure())
    }

    @Test
    fun concurrentTerminalClaimsHaveExactlyOneWinner() {
        repeat(500) {
            val state = ThroughputTerminalState()
            val ready = CountDownLatch(3)
            val start = CountDownLatch(1)
            val winners = AtomicInteger(0)
            val actions = listOf<() -> Boolean>(
                state::claimSuccess,
                state::claimFailure,
                state::cancel
            )
            val threads = actions.map { action ->
                Thread {
                    ready.countDown()
                    start.await()
                    if (action()) winners.incrementAndGet()
                }.apply { start() }
            }
            ready.await()
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, winners.get())
        }
    }
}
