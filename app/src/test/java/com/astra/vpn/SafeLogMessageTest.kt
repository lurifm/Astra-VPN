package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLogMessageTest {
    @Test fun includesOperationContextAndExceptionType() {
        val output = SafeLogMessage.format("Network callback failed", IllegalStateException("private detail"))
        assertEquals("Network callback failed (IllegalStateException)", output)
    }

    @Test fun excludesExceptionMessageContainingCredentialsAndUrl() {
        val secret = "https://alice:super-secret@example.com/sub?token=private-token"
        val output = SafeLogMessage.format("Probe failed", IllegalArgumentException(secret))
        assertTrue(output.startsWith("Probe failed (IllegalArgumentException)"))
        assertFalse(output.contains("alice"))
        assertFalse(output.contains("super-secret"))
        assertFalse(output.contains("example.com"))
        assertFalse(output.contains("private-token"))
    }

    @Test fun excludesStackTraceText() {
        val output = SafeLogMessage.format("Operation failed", RuntimeException("stack-secret"))
        assertFalse(output.contains("stack-secret"))
        assertFalse(output.contains("\\n"))
    }
}
