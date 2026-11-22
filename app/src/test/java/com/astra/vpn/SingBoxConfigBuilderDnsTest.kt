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
    fun failedPrimaryDnsAnswerFallsBackToIndependentGoogleDoh() {
        val dns = SingBoxConfigBuilder.buildDnsConfig(SingBoxAdvancedOptions())
        val servers = dns.getJSONArray("servers")
        val fallback = servers.getJSONObject(1)
        val rules = dns.getJSONArray("rules")

        assertEquals("https", fallback.getString("type"))
        assertEquals("dns-fallback", fallback.getString("tag"))
        assertEquals("8.8.8.8", fallback.getString("server"))
        assertEquals("dns.google", fallback.getJSONObject("tls").getString("server_name"))
        assertEquals("direct", fallback.getString("detour"))

        val evaluate = rules.getJSONObject(0)
        assertEquals("evaluate", evaluate.getString("action"))
        assertEquals("dns-remote", evaluate.getString("server"))
        assertEquals("primary-doh", evaluate.getString("tag"))
        assertEquals("5s", evaluate.getString("timeout"))
        assertFalse(evaluate.getBoolean("speculative"))

        val successfulPrimary = rules.getJSONObject(1)
        assertEquals("primary-doh", successfulPrimary.getString("match_response"))
        assertTrue(successfulPrimary.getBoolean("ip_accept_any"))
        assertEquals("respond", successfulPrimary.getString("action"))

        val fallbackRule = rules.getJSONObject(2)
        assertEquals("primary-doh", fallbackRule.getString("match_response"))
        assertTrue(fallbackRule.getBoolean("invert"))
        assertEquals("route", fallbackRule.getString("action"))
        assertEquals("dns-fallback", fallbackRule.getString("server"))
        assertFalse(fallbackRule.has("timeout"))
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
