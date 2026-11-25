package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxAdvancedOptionsTest {
    @Test fun defaultsKeepConservativeAndSafeNetworkBehavior() {
        val defaults = SingBoxAdvancedOptions()

        assertEquals(1400, defaults.safeMtu)
        assertFalse(defaults.multiplexEnabled)
        assertTrue(defaults.directDomainSuffixes().isEmpty())
        assertTrue(defaults.dnsCacheEnabled)
    }

    @Test fun mtuIsClampedToSafeRange() {
        assertEquals(1280, SingBoxAdvancedOptions(mtu = 900).safeMtu)
        assertEquals(9000, SingBoxAdvancedOptions(mtu = 12000).safeMtu)
        assertEquals(1380, SingBoxAdvancedOptions(mtu = 1380).safeMtu)
    }

    @Test fun multiplexIsOnlyEnabledForVless() {
        val options = SingBoxAdvancedOptions(multiplexEnabled = true)
        assertTrue(options.shouldEnableMultiplex("vless"))
        assertFalse(options.shouldEnableMultiplex("hysteria2"))
        assertFalse(options.shouldEnableMultiplex("tuic"))
    }

    @Test fun russianDirectRoutesAreOptIn() {
        assertTrue(SingBoxAdvancedOptions().directDomainSuffixes().isEmpty())
        assertEquals(
            listOf(".ru", ".рф", ".su"),
            SingBoxAdvancedOptions(routeRussianDomainsDirect = true).directDomainSuffixes()
        )
    }
}
