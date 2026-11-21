package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServiceProbeUrlPolicyTest {
    @Test fun acceptsOnlyKnownServiceProbeEndpoints() {
        assertEquals(ServiceProbeUrlPolicy.DEFAULT_URL,
            ServiceProbeUrlPolicy.validate(ServiceProbeUrlPolicy.DEFAULT_URL))
        assertEquals("https://www.netflix.com/",
            ServiceProbeUrlPolicy.validate("https://www.netflix.com/"))
        assertEquals("https://www.youtube.com/generate_204",
            ServiceProbeUrlPolicy.validate("https://www.youtube.com/generate_204"))
    }

    @Test fun rejectsArbitraryHostsAndModifiedUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            ServiceProbeUrlPolicy.validate("https://attacker.example/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceProbeUrlPolicy.validate("https://www.netflix.com.evil.example/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceProbeUrlPolicy.validate("http://www.netflix.com/")
        }
    }
}
