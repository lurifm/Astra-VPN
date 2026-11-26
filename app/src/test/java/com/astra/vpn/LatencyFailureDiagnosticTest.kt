package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyFailureDiagnosticTest {
    @Test fun includesSanitizedMessagesAndRootCause() {
        val failure = IllegalStateException(
            "failed vless://user:password@example.com:443?uuid=secret",
            SecurityException("native command channel unavailable")
        )
        val diagnostic = LatencyFailureDiagnostic.describe(failure)
        assertTrue(diagnostic.startsWith("IllegalStateException: failed <profile-uri-redacted>"))
        assertTrue(diagnostic.contains("SecurityException: native command channel unavailable"))
        assertFalse(diagnostic.contains("password"))
        assertFalse(diagnostic.contains("secret"))
    }

    @Test fun limitsCauseDepth() {
        val failure = IllegalStateException("outer", IllegalArgumentException("middle", RuntimeException("inner")))
        assertEquals(
            "IllegalStateException: outer <- IllegalArgumentException: middle",
            LatencyFailureDiagnostic.describe(failure, maxCauses = 2)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyCauseLimit() {
        LatencyFailureDiagnostic.describe(IllegalStateException(), maxCauses = 0)
    }
}
