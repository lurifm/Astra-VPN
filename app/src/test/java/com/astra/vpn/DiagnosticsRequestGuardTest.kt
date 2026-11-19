package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRequestGuardTest {
    @Test
    fun newerRequestInvalidatesOlderCallback() {
        val guard = DiagnosticsRequestGuard()
        val first = guard.begin()
        val second = guard.begin()

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun dismissingCurrentRequestInvalidatesItsCallback() {
        val guard = DiagnosticsRequestGuard()
        val token = guard.begin()

        assertTrue(guard.invalidate(token))
        assertFalse(guard.isCurrent(token))
        assertFalse(guard.invalidate(token))
    }

    @Test
    fun dismissingOlderRequestDoesNotInvalidateNewerRequest() {
        val guard = DiagnosticsRequestGuard()
        val older = guard.begin()
        val newer = guard.begin()

        assertFalse(guard.invalidate(older))
        assertTrue(guard.isCurrent(newer))
    }
}
