package com.astra.vpn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class SeasonalSkyPolicyTest {
    @Test fun newYearSnowReplacesStarsDuringHolidayWindow() {
        assertTrue(SeasonalSkyPolicy.resolve(12, 31).newYearSnow)
        assertTrue(SeasonalSkyPolicy.resolve(1, 1).newYearSnow)
        assertTrue(SeasonalSkyPolicy.resolve(1, 2).newYearSnow)
        assertFalse(SeasonalSkyPolicy.resolve(1, 3).newYearSnow)
    }
    @Test fun snowIsVisibleInDaylightWheneverVpnIsConnected() {
        assertTrue(SeasonalSkyPolicy.isSnowActive(true, 12, 30))
        assertTrue(SeasonalSkyPolicy.isSnowActive(true, 1, 2))
        assertFalse(SeasonalSkyPolicy.isSnowActive(false, 12, 31))
        assertFalse(SeasonalSkyPolicy.isSnowActive(true, 1, 3))
    }

    @Test fun allFivePerseidTracksStartInsideVisibleBounds() {
        val starts = (0 until 5).map { SeasonalSkyPolicy.meteorStartFraction(it, 5) }
        assertTrue(starts.first() >= 0f)
        assertTrue(starts.last() + 0.25f <= 1f)
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun perseidsAndEclipseHaveBoundedAugustWindows() {
        assertTrue(SeasonalSkyPolicy.resolve(8, 10).perseids)
        assertTrue(SeasonalSkyPolicy.resolve(8, 14).perseids)
        assertFalse(SeasonalSkyPolicy.resolve(8, 9).perseids)
        assertTrue(SeasonalSkyPolicy.resolve(8, 12).eclipse)
        assertFalse(SeasonalSkyPolicy.resolve(8, 14).eclipse)
    }
}
