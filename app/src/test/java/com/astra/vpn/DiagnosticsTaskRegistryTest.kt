package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.FutureTask

class DiagnosticsTaskRegistryTest {
    private fun task() = FutureTask<Unit> { Unit }

    @Test fun replacingTaskCancelsPreviousTask() {
        val registry = DiagnosticsTaskRegistry()
        val first = task()
        val second = task()

        registry.replace(1L, first)
        registry.replace(2L, second)

        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        assertEquals(2L, registry.activeToken())
    }

    @Test fun dismissingOlderDialogCannotCancelNewerTask() {
        val registry = DiagnosticsTaskRegistry()
        val first = task()
        val second = task()
        registry.replace(1L, first)
        registry.replace(2L, second)

        assertFalse(registry.cancel(1L))
        assertFalse(second.isCancelled)
        assertEquals(2L, registry.activeToken())
    }

    @Test fun cancelCurrentAndCancelAllAreIdempotent() {
        val registry = DiagnosticsTaskRegistry()
        val current = task()
        registry.replace(7L, current)

        assertTrue(registry.cancel(7L))
        assertFalse(registry.cancel(7L))
        assertTrue(current.isCancelled)
        assertEquals(null, registry.activeToken())

        val another = task()
        registry.replace(8L, another)
        assertTrue(registry.cancelAll())
        assertFalse(registry.cancelAll())
        assertTrue(another.isCancelled)
    }
}
