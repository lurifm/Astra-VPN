package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceServerSelectionPolicyTest {
    @Test fun selectsLowestMeasuredSuccessfulEndpoint() {
        val result = ServiceServerSelectionPolicy.bestProfile(
            mapOf("a" to "profile-a", "b" to "profile-b", "c" to "profile-c"),
            mapOf("a" to 120L, "b" to 42L, "c" to 88L)
        )
        assertEquals(ServiceServerSelectionPolicy.Candidate("profile-b", 42L), result)
    }

    @Test fun ignoresMissingZeroAndNegativeMeasurements() {
        val result = ServiceServerSelectionPolicy.bestProfile(
            mapOf("a" to "profile-a", "b" to "profile-b", "c" to "profile-c"),
            mapOf("a" to null, "b" to 0L, "c" to -1L)
        )
        assertNull(result)
    }

    @Test fun deterministicTieBreakDoesNotDependOnMapIterationOrder() {
        val result = ServiceServerSelectionPolicy.bestProfile(
            linkedMapOf("z" to "z-profile", "a" to "a-profile"),
            mapOf("z" to 50L, "a" to 50L)
        )
        assertEquals("a-profile", result?.profile)
    }
}
