package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyFailureDiagnosticTest {
    @Test fun includesRootCauseClassWithoutExceptionMessage() {
        val failure = IllegalStateException("secret server address", SecurityException("secret token"))
        assertEquals(
            "IllegalStateException <- SecurityException",
            LatencyFailureDiagnostic.describe(failure)
        )
    }

    @Test fun limitsCauseDepth() {
        val failure = IllegalStateException("outer", IllegalArgumentException("middle", RuntimeException("inner")))
        assertEquals(
            "IllegalStateException <- IllegalArgumentException",
            LatencyFailureDiagnostic.describe(failure, maxCauses = 2)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyCauseLimit() {
        LatencyFailureDiagnostic.describe(IllegalStateException(), maxCauses = 0)
    }
}
