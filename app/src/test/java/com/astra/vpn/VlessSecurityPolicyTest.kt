package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessSecurityPolicyTest {
    @Test
    fun acceptsExplicitlySupportedSecurityModes() {
        assertTrue(VlessSecurityPolicy.isSupported(null))
        assertTrue(VlessSecurityPolicy.isSupported(""))
        assertTrue(VlessSecurityPolicy.isSupported("none"))
        assertTrue(VlessSecurityPolicy.isSupported("tls"))
        assertTrue(VlessSecurityPolicy.isSupported("reality"))
        assertTrue(VlessSecurityPolicy.isSupported("TLS"))
        assertTrue(VlessSecurityPolicy.isSupported("REALITY"))
    }

    @Test
    fun rejectsUnknownModesInsteadOfSilentlyDisablingTls() {
        assertFalse(VlessSecurityPolicy.isSupported("xtls"))
        assertFalse(VlessSecurityPolicy.isSupported("utls"))
        assertFalse(VlessSecurityPolicy.isSupported("unknown"))
    }
}
