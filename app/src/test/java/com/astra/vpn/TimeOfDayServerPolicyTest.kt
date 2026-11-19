package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeOfDayServerPolicyTest {
    @Test fun mapsLocalHoursToWindows() {
        assertEquals(TimeOfDayServerPolicy.Window.NIGHT, TimeOfDayServerPolicy.window(2))
        assertEquals(TimeOfDayServerPolicy.Window.MORNING, TimeOfDayServerPolicy.window(8))
        assertEquals(TimeOfDayServerPolicy.Window.DAY, TimeOfDayServerPolicy.window(14))
        assertEquals(TimeOfDayServerPolicy.Window.EVENING, TimeOfDayServerPolicy.window(20))
    }

    @Test fun onlyAddsSmallBonusForConfiguredProtocol() {
        val prefs = mapOf(TimeOfDayServerPolicy.Window.EVENING to setOf("tuic"))
        assertEquals(8, TimeOfDayServerPolicy.preferredScoreBonus("tuic://host", 19, prefs))
        assertEquals(0, TimeOfDayServerPolicy.preferredScoreBonus("vless://host", 19, prefs))
    }
}
