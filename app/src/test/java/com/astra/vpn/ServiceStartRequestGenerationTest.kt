package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartRequestGenerationTest {
    @Test
    fun newerStartInvalidatesPreviousRequest() {
        val generation = ServiceStartRequestGeneration()
        val first = generation.next()
        val second = generation.next()
        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
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
}
