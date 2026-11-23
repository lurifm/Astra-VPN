package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStateEventPolicyTest {
    @Test
    fun acceptsFirstValidServiceEvent() {
        assertTrue(ServiceStateEventPolicy.shouldAccept(1L, null))
    }

    @Test
    fun acceptsEventsFromCurrentRequest() {
        assertTrue(ServiceStateEventPolicy.shouldAccept(7L, 7L))
    }

    @Test
    fun rejectsDelayedTerminalEventFromOlderRequest() {
        assertFalse(ServiceStateEventPolicy.shouldAccept(4L, 5L))
    }

    @Test
    fun acceptsNewerRequestAndAdvancesHighWaterMark() {
        assertTrue(ServiceStateEventPolicy.shouldAccept(8L, 7L))
    }

    @Test
    fun rejectsMissingOrInvalidGeneration() {
        assertFalse(ServiceStateEventPolicy.shouldAccept(Long.MIN_VALUE, null))
        assertFalse(ServiceStateEventPolicy.shouldAccept(-1L, 0L))
    }
}
