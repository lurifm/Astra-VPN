package com.astra.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxThroughputTestConfigTest {
    private val source = JSONObject()
        .put("outbounds", org.json.JSONArray()
            .put(JSONObject().put("type", "vless").put("tag", "proxy").put("server", "example.com").put("server_port", 443))
            .put(JSONObject().put("type", "direct").put("tag", "direct")))
        .put("dns", JSONObject().put("servers", org.json.JSONArray()))

    @Test
    fun createsLoopbackOnlyMixedInboundWithoutTun() {
        val result = SingBoxThroughputTestConfig.buildFromSource(source, 18080)
        val config = JSONObject(result.json)
        val inbounds = config.getJSONArray("inbounds")
        val inbound = inbounds.getJSONObject(0)

        assertEquals(1, inbounds.length())
        assertEquals("mixed", inbound.getString("type"))
        assertEquals(SingBoxThroughputTestConfig.INBOUND_TAG, inbound.getString("tag"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(18080, inbound.getInt("listen_port"))
        assertFalse(result.json.contains("\"type\":\"tun\""))
        assertEquals("127.0.0.1", result.proxyHost)
        assertEquals(18080, result.proxyPort)
    }

    @Test
    fun routesProbeInboundToOnlyTheSelectedProxyOutbound() {
        val config = JSONObject(SingBoxThroughputTestConfig.buildFromSource(source, 18081).json)
        val outbounds = config.getJSONArray("outbounds")
        assertEquals(2, outbounds.length())
        assertEquals(SingBoxThroughputTestConfig.OUTBOUND_TAG, outbounds.getJSONObject(0).getString("tag"))
        assertEquals("direct", outbounds.getJSONObject(1).getString("tag"))

        val route = config.getJSONObject("route")
        assertEquals("direct", route.getString("final"))
        val rule = route.getJSONArray("rules").getJSONObject(0)
        assertEquals(
            SingBoxThroughputTestConfig.INBOUND_TAG,
            rule.getJSONArray("inbound").getString(0)
        )
        assertEquals(SingBoxThroughputTestConfig.OUTBOUND_TAG, rule.getString("outbound"))
        assertTrue(config.has("dns"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLocalPort() {
        SingBoxThroughputTestConfig.buildFromSource(source, 0)
    }
}
