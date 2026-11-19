package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelSecurityAuditTest {
    @Test
    fun recognizesEncryptedDnsHijackAndDualStackTunnel() {
        val config = """
            {
              "dns": {
                "servers": [
                  {"type":"https","tag":"secure-dns","tls":{"enabled":true}},
                  {"type":"local","tag":"local-dns"}
                ],
                "final":"secure-dns"
              },
              "inbounds": [
                {"type":"tun","auto_route":true,"address":["172.19.0.1/30","fdfe:dcba::1/126"]}
              ],
              "route": {
                "rules":[{"protocol":["dns"],"action":"hijack-dns"}]
              }
            }
        """.trimIndent()
        val result = TunnelSecurityAudit.inspect(config)
        assertTrue(result.dnsRoutingProtected)
        assertTrue(result.ipv4TunnelConfigured)
        assertTrue(result.ipv6RoutingConfigured)
    }

    @Test
    fun detectsMissingDnsHijackAndIpv6Route() {
        val config = """
            {
              "dns": {
                "servers":[{"type":"local","tag":"local-dns"}],
                "final":"local-dns"
              },
              "inbounds":[
                {"type":"tun","auto_route":false,"address":["172.19.0.1/30"]}
              ],
              "route":{"rules":[]}
            }
        """.trimIndent()
        val result = TunnelSecurityAudit.inspect(config)
        assertFalse(result.dnsRoutingProtected)
        assertFalse(result.ipv6RoutingConfigured)
    }
}
