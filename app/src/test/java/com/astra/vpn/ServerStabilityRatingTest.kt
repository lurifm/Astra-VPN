package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerStabilityRatingTest {
    @Test fun stableFastServerScoresHigherThanSlowUnstableOne() {
        val stable = ServerStabilityRating.score(listOf(40L, 42L, 41L, 39L))!!
        val unstable = ServerStabilityRating.score(listOf(80L, 900L, null, 700L))!!
        assertTrue(stable > unstable)
    }
    @Test fun noSamplesReturnsNullAndAllFailuresZero() {
        assertNull(ServerStabilityRating.score(emptyList()))
        assertEquals(0, ServerStabilityRating.score(listOf(null, null)))
    }
}
