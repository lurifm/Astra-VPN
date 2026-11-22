package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingBoxUrlTestTagMappingTest {
    @Test
    fun outboundTagsAreStableWithinSessionButIsolatedAcrossSessions() {
        val firstSession = SingBoxUrlTestConfig.outboundTag("vless://node", "session-a")
        val sameSession = SingBoxUrlTestConfig.outboundTag("vless://node", "session-a")
        val nextSession = SingBoxUrlTestConfig.outboundTag("vless://node", "session-b")

        assertEquals(firstSession, sameSession)
        org.junit.Assert.assertNotEquals(firstSession, nextSession)
        org.junit.Assert.assertTrue(firstSession.matches(Regex("astra-node-[0-9a-f]{16}")))
    }

    @Test
    fun outboundTagMapsToItsOwnUrlTestGroup() {
        val outbound = "astra-node-0123456789abcdef"

        assertEquals(
            "astra-test-0123456789abcdef",
            SingBoxUrlTestConfig.groupTagForOutbound(outbound)
        )
    }

    @Test
    fun groupTagMapsBackToOutboundTag() {
        assertEquals(
            "astra-node-0123456789abcdef",
            SingBoxUrlTestConfig.outboundTagForGroup("astra-test-0123456789abcdef")
        )
    }

    @Test
    fun malformedGroupTagsAreIgnored() {
        assertNull(SingBoxUrlTestConfig.outboundTagForGroup("other-0123456789abcdef"))
        assertNull(SingBoxUrlTestConfig.outboundTagForGroup("astra-test-short"))
        assertNull(SingBoxUrlTestConfig.outboundTagForGroup("astra-test-0123456789ABCDEF"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedOutboundTagCannotCreateGroupTag() {
        SingBoxUrlTestConfig.groupTagForOutbound("astra-node-invalid")
    }
}
