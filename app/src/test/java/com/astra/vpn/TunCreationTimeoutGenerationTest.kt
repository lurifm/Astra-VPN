package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunCreationTimeoutGenerationTest {
    @Test
    fun newerWaitInvalidatesPreviousTimeout() {
        val generation = TunCreationTimeoutGeneration()

        val first = generation.next()
        val second = generation.next()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun cancellationInvalidatesCurrentTimeout() {
        val generation = TunCreationTimeoutGeneration()
        val token = generation.next()

        generation.invalidate()

        assertFalse(generation.isCurrent(token))
    }

    @Test
    fun tokensIncreaseMonotonically() {
        val generation = TunCreationTimeoutGeneration()

        val first = generation.next()
        generation.invalidate()
        val second = generation.next()

        assertTrue(second > first)
    }
}
