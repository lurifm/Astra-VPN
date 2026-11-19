package com.astra.vpn

import org.json.JSONObject

/**
 * Static audit of the sing-box tunnel config. This verifies intended routing
 * safeguards; it is not a substitute for an external leak test on a device.
 */
internal object TunnelSecurityAudit {
    data class Result(
        val encryptedDnsConfigured: Boolean,
        val dnsHijackingEnabled: Boolean,
        val ipv4TunnelConfigured: Boolean,
        val ipv6TunnelConfigured: Boolean,
        val automaticRoutingEnabled: Boolean
    ) {
        val dnsRoutingProtected: Boolean
            get() = encryptedDnsConfigured && dnsHijackingEnabled
        val ipv6RoutingConfigured: Boolean
            get() = ipv6TunnelConfigured && automaticRoutingEnabled
    }

    fun inspect(configJson: String): Result {
        val root = JSONObject(configJson)
        val dns = root.optJSONObject("dns")
        val dnsServers = dns?.optJSONArray("servers")
        val encryptedServerTags = buildSet {
            if (dnsServers != null) {
                for (index in 0 until dnsServers.length()) {
                    val server = dnsServers.optJSONObject(index) ?: continue
                    val type = server.optString("type")
                    val tlsEnabled = server.optJSONObject("tls")?.optBoolean("enabled", false) == true
                    if ((type == "https" || type == "tls") && tlsEnabled) {
                        add(server.optString("tag"))
                    }
                }
            }
        }
        val finalDns = dns?.optString("final").orEmpty()
        val encryptedDns = finalDns.isNotBlank() && finalDns in encryptedServerTags

        val route = root.optJSONObject("route")
        val rules = route?.optJSONArray("rules")
        var dnsHijack = false
        if (rules != null) {
            for (index in 0 until rules.length()) {
                val rule = rules.optJSONObject(index) ?: continue
                val protocols = rule.optJSONArray("protocol")
                val matchesDns = protocols?.let { values ->
                    (0 until values.length()).any { values.optString(it) == "dns" }
                } ?: (rule.optString("protocol") == "dns")
                if (matchesDns && rule.optString("action") == "hijack-dns") {
                    dnsHijack = true
                    break
                }
            }
        }

        val inbounds = root.optJSONArray("inbounds")
        var ipv4 = false
        var ipv6 = false
        var autoRoute = false
        if (inbounds != null) {
            for (index in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(index) ?: continue
                if (inbound.optString("type") != "tun") continue
                autoRoute = inbound.optBoolean("auto_route", false)
                val addresses = inbound.optJSONArray("address")
                if (addresses != null) {
                    for (addressIndex in 0 until addresses.length()) {
                        val address = addresses.optString(addressIndex)
                        if (address.contains(":")) ipv6 = true
                        else if (address.contains(".")) ipv4 = true
                    }
                }
            }
        }
        return Result(encryptedDns, dnsHijack, ipv4, ipv6, autoRoute)
    }
}
