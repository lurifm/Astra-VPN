package com.astra.vpn

/** Pure color policy for the primary VPN button. Unknown latency is never treated as success or failure. */
internal object ConnectButtonHealthPolicy {
    enum class Color { SILVER, GREEN, AMBER, RED }

    fun resolve(
        connected: Boolean,
        latencyMs: Long?,
        tunnelInternetOk: Boolean?
    ): Color {
        if (!connected) return Color.SILVER
        if (tunnelInternetOk == false) return Color.RED
        if (latencyMs == null || latencyMs <= 0L) return Color.AMBER
        return if (latencyMs < 200L) Color.GREEN else Color.AMBER
    }
}
