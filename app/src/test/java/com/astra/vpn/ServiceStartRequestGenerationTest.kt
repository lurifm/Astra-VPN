package com.astra.vpn

import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartRequestGenerationTest {
    @Test
    fun restoresPersistedGenerationAfterProcessRecreation() {
        val oldProcess = ServiceStartRequestGeneration()
        repeat(12) { oldProcess.next() }
        val persisted = oldProcess.current()

        // A new process starts with a fresh in-memory counter.
        val recreatedProcess = ServiceStartRequestGeneration()
        recreatedProcess.restoreAtLeast(persisted)
        val nextRequest = recreatedProcess.next()

        assertTrue(nextRequest > persisted)
        assertTrue(recreatedProcess.isCurrent(nextRequest))
    }

    @Test
    fun restoringOlderGenerationNeverMovesCounterBackwards() {
        val generation = ServiceStartRequestGeneration()
        val current = generation.next()
        generation.restoreAtLeast(current - 1L)
        assertEquals(current, generation.current())
    }

    @Test
    fun ignoresNegativePersistedGeneration() {
        val generation = ServiceStartRequestGeneration()
        generation.restoreAtLeast(-1L)
        assertEquals(0L, generation.current())
    }

    @Test
    fun newerStartInvalidatesPreviousRequest() {
        val generation = ServiceStartRequestGeneration()
        val first = generation.next()
        val second = generation.next()
        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun currentSnapshotMatchesLatestStartAndChangesAfterInvalidation() {
        val generation = ServiceStartRequestGeneration()
        val initial = generation.current()
        val request = generation.next()
        assertEquals(request, generation.current())
        assertTrue(generation.isCurrent(initial).not())
        generation.invalidate()
        assertFalse(generation.isCurrent(request))
        assertTrue(generation.current() > request)
    }

    @Test
    fun stopInvalidatesPendingRequest() {
        val generation = ServiceStartRequestGeneration()
        val request = generation.next()
        generation.invalidate()
        assertFalse(generation.isCurrent(request))
    }

    @Test
    fun generationIsMonotonicAcrossInvalidation() {
        val generation = ServiceStartRequestGeneration()
        val first = generation.next()
        generation.invalidate()
        val second = generation.next()
        assertTrue(second > first)
    }

    @Test
    fun concurrentStartsReceiveUniqueMonotonicTokens() {
        val generation = ServiceStartRequestGeneration()
        val startGate = CountDownLatch(1)
        val finished = CountDownLatch(32)
        val tokens = Collections.synchronizedList(mutableListOf<Long>())

        repeat(32) {
            Thread {
                try {
                    startGate.await()
                    tokens.add(generation.next())
                } finally {
                    finished.countDown()
                }
            }.start()
        }

        startGate.countDown()
        finished.await()

        val snapshot = synchronized(tokens) { tokens.toList() }
        assertEquals(32, snapshot.size)
        assertEquals(32, snapshot.toSet().size)

        val latest = snapshot.maxOrNull()!!
        assertTrue(generation.isCurrent(latest))
        snapshot.filter { it != latest }.forEach { token ->
            assertFalse(generation.isCurrent(token))
        }
    }

    @Test
    fun repeatedStopInvalidationKeepsNextStartCurrent() {
        val generation = ServiceStartRequestGeneration()
        val first = generation.next()

        generation.invalidate()
        generation.invalidate()

        val next = generation.next()
        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(next))
    }
}
