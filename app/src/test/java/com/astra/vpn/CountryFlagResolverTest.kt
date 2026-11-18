package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class CountryFlagResolverTest {
    @Test fun resolvesCountryNamesAndAliases() {
        assertEquals("🇩🇪", CountryFlagResolver.flagFor("Germany #03"))
        assertEquals("🇬🇧", CountryFlagResolver.flagFor("UK-London"))
        assertEquals("🇺🇸", CountryFlagResolver.flagFor("New York USA"))
        assertEquals("🇨🇦", CountryFlagResolver.flagFor("Канада 01"))
        assertEquals("🇹🇷", CountryFlagResolver.flagFor("Turkey"))
    }

    @Test fun doesNotGuessFromUnrelatedSubstrings() {
        assertEquals("🌐", CountryFlagResolver.flagFor("scan-node-01"))
        assertEquals("🌐", CountryFlagResolver.flagFor("canary-edge"))
        assertEquals("🌐", CountryFlagResolver.flagFor("unknown.example.net"))
        assertEquals("🌐", CountryFlagResolver.flagFor(""))
    }

    @Test fun recognizesAdditionalCommonServerLocations() {
        assertEquals("🇵🇹", CountryFlagResolver.flagFor("Portugal-02"))
        assertEquals("🇯🇵", CountryFlagResolver.flagFor("Tokyo Japan"))
        assertEquals("🇸🇬", CountryFlagResolver.flagFor("Singapore"))
        assertEquals("🇦🇺", CountryFlagResolver.flagFor("Australia Sydney"))
        assertEquals("🇹🇭", CountryFlagResolver.flagFor("Thailand"))
        assertEquals("🇿🇦", CountryFlagResolver.flagFor("South Africa"))
    }

    @Test fun resolvesCountryNamesAndAliasesWithoutFalsePositives() {
        assertEquals("🇨🇳", CountryFlagResolver.flagFor("CN • Shanghai"))
        assertEquals("🌐", CountryFlagResolver.flagFor("random"))
    }

    @Test fun recognizesExplicitRussianCountryName() {
        assertEquals("🇷🇺", CountryFlagResolver.flagFor("Россия • Москва"))
    }
}


class TcpLatencyProbeTest {
    @Test fun medianIgnoresOneSlowOutlier() {
        assertEquals(24L, TcpLatencyProbe.median(listOf(22L, 24L, 900L)))
    }

    @Test fun medianAveragesMiddlePairForEvenSampleCount() {
        assertEquals(50L, TcpLatencyProbe.median(listOf(80L, 30L, 60L, 40L)))
    }

    @Test fun medianDoesNotDependOnInputOrder() {
        assertEquals(
            TcpLatencyProbe.median(listOf(11L, 90L, 40L, 22L, 70L)),
            TcpLatencyProbe.median(listOf(70L, 22L, 90L, 11L, 40L))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun medianRejectsEmptySamples() {
        TcpLatencyProbe.median(emptyList())
    }
}
