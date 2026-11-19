package com.astra.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet6Address
import android.provider.Settings

/** Reports observable VPN/network facts; it never claims to prove absence of leaks. */
internal object AstraNetworkSecurity {
    data class Snapshot(
        val vpnTransportActive: Boolean,
        val dnsServers: List<String>,
        val activeNetworkHasInternet: Boolean,
        val ipv6DefaultRoutePresent: Boolean
    )

    fun snapshot(context: Context): Snapshot {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val links = network?.let(manager::getLinkProperties)
        return Snapshot(
            vpnTransportActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            dnsServers = links?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
            activeNetworkHasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            ipv6DefaultRoutePresent = links?.routes?.any { route ->
                route.isDefaultRoute && route.destination.address is Inet6Address
            } == true
        )
    }

    fun openVpnSettings(context: Context) {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
