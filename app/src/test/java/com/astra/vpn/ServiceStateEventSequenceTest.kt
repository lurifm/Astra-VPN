package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStateEventSequenceTest {
    @Test
    fun continuesAfterProcessRecreation() {
        val oldProcess = ServiceStateEventSequence()
        repeat(5) { oldProcess.next() }
        val recreated = ServiceStateEventSequence()
        recreated.restoreAtLeast(oldProcess.current())
        assertTrue(recreated.next() > oldProcess.current())
    }

    @Test
    fun restoreNeverMovesSequenceBackwards() {
        val sequence = ServiceStateEventSequence()
        val current = sequence.next()
        sequence.restoreAtLeast(current - 1L)
        assertEquals(current, sequence.current())
    }

    @Test
    fun concurrentPublishersReceiveUniqueIncreasingSequences() {
        val sequence = ServiceStateEventSequence()
        val gate = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(64)
        val values = java.util.Collections.synchronizedList(mutableListOf<Long>())

        repeat(64) {
            Thread {
                try {
                    gate.await()
                    values.add(sequence.next())
                } finally {
                    done.countDown()
                }
            }.start()
        }
        gate.countDown()
        done.await()

        val snapshot = synchronized(values) { values.toList() }
        assertEquals(64, snapshot.size)
        assertEquals(64, snapshot.toSet().size)
        assertEquals((1L..64L).toSet(), snapshot.toSet())
        assertEquals(64L, sequence.current())
    }

    @Test
    fun invalidPersistedValueIsIgnored() {
        val sequence = ServiceStateEventSequence()
        sequence.restoreAtLeast(-1L)
        assertEquals(0L, sequence.current())
    }
}
