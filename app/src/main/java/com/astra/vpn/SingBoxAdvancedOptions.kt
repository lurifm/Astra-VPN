package com.astra.vpn

/**
 * Advanced per-profile sing-box tuning.
 *
 * Direct routing is opt-in because it intentionally bypasses the VPN for matching
 * Russian domains. Multiplexing is only emitted for VLESS; other supported
 * transports keep their protocol-native stream handling.
 */
data class SingBoxAdvancedOptions(
    val mtu: Int = DEFAULT_MTU,
    val multiplexEnabled: Boolean = false,
    val routeRussianDomainsDirect: Boolean = false,
    val dnsCacheEnabled: Boolean = true
) {
    val safeMtu: Int get() = mtu.coerceIn(MIN_MTU, MAX_MTU)

    fun shouldEnableMultiplex(protocol: String): Boolean =
        multiplexEnabled && protocol.equals("vless", ignoreCase = true)

    fun directDomainSuffixes(): List<String> =
        if (routeRussianDomainsDirect) RUSSIAN_DOMAIN_SUFFIXES else emptyList()

    companion object {
        const val DEFAULT_MTU = 1400
        const val MIN_MTU = 1280
        const val MAX_MTU = 9000
        val RUSSIAN_DOMAIN_SUFFIXES = listOf(".ru", ".рф", ".su")
    }
}
