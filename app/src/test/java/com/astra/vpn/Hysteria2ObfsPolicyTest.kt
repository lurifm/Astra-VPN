package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Hysteria2ObfsPolicyTest {
    @Test
    fun acceptsNoObfuscationWhenNoObfuscationCredentialsAreProvided() {
        assertTrue(Hysteria2ObfsPolicy.isValid(null, null))
        assertTrue(Hysteria2ObfsPolicy.isValid("", "  "))
    }

    @Test
    fun acceptsSalamanderWithNonEmptyPasswordCaseInsensitively() {
        assertTrue(Hysteria2ObfsPolicy.isValid("salamander", "secret"))
        assertTrue(Hysteria2ObfsPolicy.isValid("SALAMANDER", "secret"))
    }

    @Test
    fun rejectsUnknownObfuscationInsteadOfSilentlyDroppingIt() {
        assertFalse(Hysteria2ObfsPolicy.isValid("obfs4", "secret"))
        assertFalse(Hysteria2ObfsPolicy.isValid("unknown", null))
    }

    @Test
    fun requiresPasswordForSalamander() {
        assertFalse(Hysteria2ObfsPolicy.isValid("salamander", null))
        assertFalse(Hysteria2ObfsPolicy.isValid("salamander", " "))
    }

    @Test
    fun rejectsOrphanedObfuscationPassword() {
        assertFalse(Hysteria2ObfsPolicy.isValid(null, "secret"))
    }
}
