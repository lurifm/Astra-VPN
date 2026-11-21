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
    @Test fun perseidsAndEclipseHaveBoundedAugustWindows() {
        assertTrue(SeasonalSkyPolicy.resolve(8, 10).perseids)
        assertTrue(SeasonalSkyPolicy.resolve(8, 14).perseids)
        assertFalse(SeasonalSkyPolicy.resolve(8, 9).perseids)
        assertTrue(SeasonalSkyPolicy.resolve(8, 12).eclipse)
        assertFalse(SeasonalSkyPolicy.resolve(8, 14).eclipse)
    }
}
