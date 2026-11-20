package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecycleGenerationTest {
    @Test fun newServerInvalidatesCallbacksFromPreviousServer() {
        val generation = ServiceLifecycleGeneration()
        val oldServer = generation.advance()
        val replacementServer = generation.advance()

        assertFalse(generation.isCurrent(oldServer))
        assertTrue(generation.isCurrent(replacementServer))
    }

    @Test fun stoppingInvalidatesCurrentServerCallbacks() {
        val generation = ServiceLifecycleGeneration()
        val activeServer = generation.advance()

        generation.advance() // stop invalidates all callbacks from the active server

        assertFalse(generation.isCurrent(activeServer))
    }

    @Test fun generationNeverMovesBackwards() {
        val generation = ServiceLifecycleGeneration()
        val first = generation.advance()
        val second = generation.advance()
        val third = generation.advance()

        assertTrue(first < second)
        assertTrue(second < third)
        assertTrue(generation.isCurrent(third))
    }
}
