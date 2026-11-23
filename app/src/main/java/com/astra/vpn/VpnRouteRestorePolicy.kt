package com.astra.vpn

/**
 * Bounds the brief race between persisted service CONNECTED state and Android
 * publishing the corresponding VPN Network to ConnectivityManager.
 */
internal object VpnRouteRestorePolicy {
    const val MAX_RETRIES = 6
    const val RETRY_DELAY_MS = 250L

    fun shouldRetry(
        savedConnected: Boolean,
        androidVpnActive: Boolean,
        stopRequested: Boolean,
        attempt: Int
    ): Boolean =
        savedConnected &&
            !androidVpnActive &&
            !stopRequested &&
            attempt in 0 until MAX_RETRIES
}
