package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightSkyAnimationPolicyTest {
    @Test fun nightWindowHandlesMidnightBoundary() {
        assertTrue(NightSkyAnimationPolicy.isNight(21))
        assertTrue(NightSkyAnimationPolicy.isNight(0))
        assertTrue(NightSkyAnimationPolicy.isNight(5))
        assertFalse(NightSkyAnimationPolicy.isNight(6))
        assertFalse(NightSkyAnimationPolicy.isNight(20))
        assertFalse(NightSkyAnimationPolicy.isNight(12))
    }

    @Test fun revealIsSmoothAndClamped() {
        assertEquals(0f, NightSkyAnimationPolicy.revealProgress(-10L), 0.0001f)
        assertEquals(0f, NightSkyAnimationPolicy.revealProgress(0L), 0.0001f)
        assertEquals(0.5f, NightSkyAnimationPolicy.revealProgress(1_200L), 0.0001f)
        assertEquals(1f, NightSkyAnimationPolicy.revealProgress(2_400L), 0.0001f)
        assertEquals(1f, NightSkyAnimationPolicy.revealProgress(9_000L), 0.0001f)
    }

    @Test fun meteorFlightHasBoundedWindow() {
        assertEquals(0f, NightSkyAnimationPolicy.meteorProgress(0L, 0L), 0.0001f)
        assertTrue(NightSkyAnimationPolicy.meteorProgress(1_150L, 0L) > 0f)
        assertEquals(0f, NightSkyAnimationPolicy.meteorProgress(2_300L, 0L), 0.0001f)
    }
}
