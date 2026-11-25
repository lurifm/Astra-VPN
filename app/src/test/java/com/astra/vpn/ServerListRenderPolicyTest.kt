package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerListRenderPolicyTest {
    @Test fun initialRenderIsCappedAtOneHundredRows() {
        val profiles = (1..1_000).map { "vless://node$it" }
        assertEquals(100, ServerListRenderPolicy.visibleProfiles(profiles, ServerListRenderPolicy.INITIAL_PAGE_SIZE).size)
    }

    @Test fun subsequentPagesRevealAnotherHundredWithoutSkippingProfiles() {
        val profiles = (1..250).map { "vless://node$it" }
        val first = ServerListRenderPolicy.visibleProfiles(profiles, ServerListRenderPolicy.INITIAL_PAGE_SIZE)
        val second = ServerListRenderPolicy.visibleProfiles(profiles, ServerListRenderPolicy.nextVisibleLimit(first.size, profiles.size))
        assertEquals(200, second.size)
        assertEquals(profiles.take(200), second)
    }

    @Test fun finalPageDoesNotExceedAvailableProfiles() {
        assertEquals(250, ServerListRenderPolicy.nextVisibleLimit(200, 250))
        assertEquals(0, ServerListRenderPolicy.nextVisibleLimit(100, 0))
    }

    @Test fun negativeLimitDoesNotProduceRows() {
        assertEquals(emptyList<String>(), ServerListRenderPolicy.visibleProfiles(listOf("vless://a"), -1))
    }
}
