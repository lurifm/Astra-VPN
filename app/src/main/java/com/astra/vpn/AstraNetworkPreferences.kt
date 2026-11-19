package com.astra.vpn

import android.content.Context

/** User-controlled routing and TUN MTU preferences, validated at the boundary. */
internal data class AstraNetworkPreferences(
    val routingPreset: RoutingPreset = RoutingPreset.ALL_VPN,
    val mtu: Int = DEFAULT_MTU
) {
    enum class RoutingPreset { ALL_VPN, LOCAL_DIRECT }

    init { require(mtu in MIN_MTU..MAX_MTU) }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ROUTING, routingPreset.name)
            .putInt(KEY_MTU, mtu)
            .apply()
    }

    companion object {
        const val DEFAULT_MTU = 1400
        const val MIN_MTU = 1280
        const val MAX_MTU = 1500
        private const val PREFS = "astra_network_preferences"
        private const val KEY_ROUTING = "routing_preset"
        private const val KEY_MTU = "tun_mtu"

        fun load(context: Context): AstraNetworkPreferences {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val routing = runCatching {
                RoutingPreset.valueOf(prefs.getString(KEY_ROUTING, RoutingPreset.ALL_VPN.name)!!)
            }.getOrDefault(RoutingPreset.ALL_VPN)
            val mtu = prefs.getInt(KEY_MTU, DEFAULT_MTU).coerceIn(MIN_MTU, MAX_MTU)
            return AstraNetworkPreferences(routing, mtu)
        }
    }
}
