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

    @Test fun recognizesExplicitRussianCountryName() {
        assertEquals("🇷🇺", CountryFlagResolver.flagFor("Россия • Москва"))
    }
}
