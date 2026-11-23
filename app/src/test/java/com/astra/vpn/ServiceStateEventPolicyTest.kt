package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStateEventPolicyTest {
    @Test
    fun acceptsFirstValidServiceEvent() {
        assertTrue(ServiceStateEventPolicy.shouldAccept(1L, 1L, null, null))
    }

    @Test
    fun acceptsLaterEventFromSameRequest() {
        assertTrue(ServiceStateEventPolicy.shouldAccept(7L, 9L, 7L, 8L))
    }

    @Test
    fun rejectsReorderedEarlierEventFromSameRequest() {
        assertFalse(ServiceStateEventPolicy.shouldAccept(7L, 8L, 7L, 9L))
    }

    @Test
    fun rejectsDuplicateEventSequence() {
        assertFalse(ServiceStateEventPolicy.shouldAccept(7L, 9L, 7L, 9L))
    }

    @Test
    fun rejectsDelayedEventFromOlderRequestEvenWithLargeSequence() {
        assertFalse(ServiceStateEventPolicy.shouldAccept(6L, 100L, 7L, 1L))
    }

    @Test
    fun acceptsNewRequestEvenWhenItsSequenceRestartsLower() {
        assertTrue(ServiceStateEventPolicy.shouldAccept(8L, 1L, 7L, 100L))
    }

    @Test
    fun rejectsMissingOrInvalidVersion() {
        assertFalse(ServiceStateEventPolicy.shouldAccept(Long.MIN_VALUE, 1L, null, null))
        assertFalse(ServiceStateEventPolicy.shouldAccept(1L, Long.MIN_VALUE, null, null))
        assertFalse(ServiceStateEventPolicy.shouldAccept(-1L, 0L, 0L, 0L))
    }
}
