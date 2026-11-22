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
    fun racesPrimaryAndFallbackDohAndReturnsFirstSuccessfulResponse() {
        val dns = SingBoxConfigBuilder.buildDnsConfig(SingBoxAdvancedOptions())
        val servers = dns.getJSONArray("servers")
        val fallback = servers.getJSONObject(1)
        val rules = dns.getJSONArray("rules")

        assertEquals("https", fallback.getString("type"))
        assertEquals("dns-fallback", fallback.getString("tag"))
        assertEquals("8.8.8.8", fallback.getString("server"))
        assertEquals("dns.google", fallback.getJSONObject("tls").getString("server_name"))
        assertEquals("direct", fallback.getString("detour"))

        val primaryEvaluate = rules.getJSONObject(0)
        assertEquals("evaluate", primaryEvaluate.getString("action"))
        assertEquals("dns-remote", primaryEvaluate.getString("server"))
        assertEquals("primary-doh", primaryEvaluate.getString("tag"))
        assertEquals("5s", primaryEvaluate.getString("timeout"))

        val primaryRace = rules.getJSONObject(1)
        assertEquals("primary-doh", primaryRace.getString("match_response"))
        assertEquals("NOERROR", primaryRace.getString("response_rcode"))
        assertEquals("respond", primaryRace.getString("action"))
        assertTrue(primaryRace.getBoolean("race"))

        val fallbackEvaluate = rules.getJSONObject(2)
        assertEquals("evaluate", fallbackEvaluate.getString("action"))
        assertEquals("dns-fallback", fallbackEvaluate.getString("server"))
        assertEquals("fallback-doh", fallbackEvaluate.getString("tag"))
        assertEquals("5s", fallbackEvaluate.getString("timeout"))
        assertTrue(fallbackEvaluate.getBoolean("speculative"))

        val fallbackRace = rules.getJSONObject(3)
        assertEquals("fallback-doh", fallbackRace.getString("match_response"))
        assertEquals("NOERROR", fallbackRace.getString("response_rcode"))
        assertEquals("respond", fallbackRace.getString("action"))
        assertTrue(fallbackRace.getBoolean("race"))
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
