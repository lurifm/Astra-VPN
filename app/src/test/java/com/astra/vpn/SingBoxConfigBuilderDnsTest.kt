package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigBuilderDnsTest {
    @Test
    fun usesIpLiteralDohBootstrapOutsideVpnTunnel() {
        val dns = SingBoxConfigBuilder.buildDnsConfig(SingBoxAdvancedOptions())
        val servers = dns.getJSONArray("servers")
        val remote = servers.getJSONObject(0)

        assertEquals("https", remote.getString("type"))
        assertEquals("1.1.1.1", remote.getString("server"))
        assertEquals("cloudflare-dns.com", remote.getJSONObject("tls").getString("server_name"))
        assertEquals("direct", remote.getString("detour"))
        assertEquals("dns-remote", dns.getString("final"))
    }

    @Test
    fun enabledDnsCacheUsesBoundedOptimisticRefreshDuringHandover() {
        val dns = SingBoxConfigBuilder.buildDnsConfig(SingBoxAdvancedOptions(dnsCacheEnabled = true))
        val optimistic = dns.getJSONObject("optimistic")

        assertTrue(optimistic.getBoolean("enabled"))
        assertEquals("30m", optimistic.getString("timeout"))
        assertFalse(dns.has("independent_cache"))
    }

    @Test
    fun disabledDnsCacheDoesNotEnableOptimisticAnswers() {
        val dns = SingBoxConfigBuilder.buildDnsConfig(SingBoxAdvancedOptions(dnsCacheEnabled = false))

        assertFalse(dns.getBoolean("optimistic"))
    }
}
