package com.astra.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxThroughputTestConfigTest {
    private val first = "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&sni=example.com"
    private val second = "hysteria2://password@example.net:443?sni=example.net"

    @Test
    fun createsOneLoopbackProxyAndPinnedRoutePerProfile() {
        val result = SingBoxThroughputTestConfig.build(
            listOf(
                SingBoxThroughputTestConfig.Target(first, 19081),
                SingBoxThroughputTestConfig.Target(second, 19082)
            ),
            validateConfig = {}
        )
        val config = JSONObject(result.json)
        val inbounds = config.getJSONArray("inbounds")
        val rules = config.getJSONObject("route").getJSONArray("rules")

        assertEquals(2, inbounds.length())
        assertEquals(2, rules.length())
        assertEquals(2, result.portByOutboundTag.size)
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(index)
            assertEquals("mixed", inbound.getString("type"))
            assertEquals("127.0.0.1", inbound.getString("listen"))
            assertEquals(19081 + index, inbound.getInt("listen_port"))
            val rule = rules.getJSONObject(index)
            assertEquals(inbound.getString("tag"), rule.getJSONArray("inbound").getString(0))
            assertEquals("route", rule.getString("action"))
            assertTrue(result.portByOutboundTag.containsKey(rule.getString("outbound")))
        }
        assertFalse(config.getJSONArray("inbounds").toString().contains("\"tun\""))
    }

    @Test
    fun rejectsDuplicateLocalPorts() {
        val error = runCatching {
            SingBoxThroughputTestConfig.build(
                listOf(
                    SingBoxThroughputTestConfig.Target(first, 19081),
                    SingBoxThroughputTestConfig.Target(second, 19081)
                )
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsInvalidLocalPorts() {
        val error = runCatching {
            SingBoxThroughputTestConfig.build(
                listOf(SingBoxThroughputTestConfig.Target(first, 0))
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsTooManyTargetsBeforeBuildingConfig() {
        val targets = (0..64).map {
            SingBoxThroughputTestConfig.Target(first + "#$it", 20000 + it)
        }
        val error = runCatching { SingBoxThroughputTestConfig.build(targets) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
