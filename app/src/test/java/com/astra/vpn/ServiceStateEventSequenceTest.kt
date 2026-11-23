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
    fun invalidPersistedValueIsIgnored() {
        val sequence = ServiceStateEventSequence()
        sequence.restoreAtLeast(-1L)
        assertEquals(0L, sequence.current())
    }
}
