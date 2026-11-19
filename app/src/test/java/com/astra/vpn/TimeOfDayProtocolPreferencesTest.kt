package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeOfDayProtocolPreferencesTest {
    @Test fun roundTripsAllWindowsAndCanonicalProtocols() {
        val input = mapOf(
            TimeOfDayServerPolicy.Window.MORNING to setOf("VLESS", "hy2"),
            TimeOfDayServerPolicy.Window.EVENING to setOf("tuic", "vless")
        )
        assertEquals(input.mapValues { (_, v) -> v.map { if (it == "hy2") "hysteria2" else it.lowercase() }.toSet() },
            TimeOfDayProtocolPreferences.decode(TimeOfDayProtocolPreferences.encode(input)))
    }

    @Test fun ignoresUnknownWindowsProtocolsAndMalformedEntries() {
        val decoded = TimeOfDayProtocolPreferences.decode(
            "MORNING:VLESS,unknown,HY2;NOT_A_WINDOW:tuic;DAY:;broken;NIGHT:tuic"
        )
        assertEquals(setOf("vless", "hysteria2"), decoded[TimeOfDayServerPolicy.Window.MORNING])
        assertEquals(setOf("tuic"), decoded[TimeOfDayServerPolicy.Window.NIGHT])
        assertTrue(TimeOfDayServerPolicy.Window.DAY !in decoded)
    }

    @Test fun emptyPreferencesRemainNeutral() {
        assertTrue(TimeOfDayProtocolPreferences.decode("").isEmpty())
        assertEquals("", TimeOfDayProtocolPreferences.encode(emptyMap()))
    }

    @Test fun duplicateProtocolsAreRemovedAndOutputIsDeterministic() {
        val input = mapOf(TimeOfDayServerPolicy.Window.DAY to setOf("tuic", "TUIC", "vless"))
        assertEquals("DAY:tuic,vless", TimeOfDayProtocolPreferences.encode(input))
    }
}
