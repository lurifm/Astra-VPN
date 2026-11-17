package com.astra.vpn

import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * Android adapter used by libbox to create the system VPN TUN interface.
 * Optional platform integrations are explicitly disabled for this first client version.
 */
class AstraPlatform(
    private val vpnService: VpnService,
    private val onTunCreated: (ParcelFileDescriptor) -> Unit
) : PlatformInterface {

    private val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var interfaceListener: InterfaceUpdateListener? = null
    private var lastUnderlyingNetwork: Network? = null
    private var lastInterfaceSnapshot: InterfaceSnapshot? = null
    private var hasReportedNoInterface = false

    private data class InterfaceSnapshot(
        val name: String,
        val index: Int,
        val metered: Boolean
    )

    override fun useProcFS(): Boolean = false

    companion object {
        private const val TAG = "AstraPlatform"
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(vpnService.protect(fd)) { "Не удалось исключить сокет sing-box из VPN-туннеля" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(vpnService) == null) { "Не выдано разрешение Android VPN" }

        val builder = vpnService.Builder()
            .setSession("Astra VPN")
            .setMtu(options.mtu)

        // Per-app routing is configured by Android's VPN builder.
        // No package rules means the normal full-device VPN behavior.
        val appPrefs = vpnService.getSharedPreferences("astra_settings", android.content.Context.MODE_PRIVATE)
        val selectedPackages = appPrefs.getStringSet("vpn_app_packages", emptySet())?.toSet().orEmpty()
        val appMode = appPrefs.getString("vpn_app_mode", "exclude") ?: "exclude"
        var appliedPackageRules = 0
        selectedPackages.sorted().forEach { packageName ->
            runCatching {
                if (appMode == "include") {
                    builder.addAllowedApplication(packageName)
                } else {
                    builder.addDisallowedApplication(packageName)
                }
                appliedPackageRules++
            }.onFailure { error ->
                Log.w(TAG, "Не удалось применить правило для приложения $packageName", error)
            }
        }
        // Validate after applying rules: if every saved package is missing,
        // Android would otherwise interpret an empty allow-list as full VPN.
        AppRoutingPolicy.validate(appMode, selectedPackages.size, appliedPackageRules)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val ipv4 = options.inet4Address
        val hasIpv4Address = ipv4.hasNext()
        while (ipv4.hasNext()) {
            val address = ipv4.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val ipv6 = options.inet6Address
        val hasIpv6Address = ipv6.hasNext()
        while (ipv6.hasNext()) {
            val address = ipv6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) {
                    builder.addDnsServer(dns.next())
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val routes4 = options.inet4RouteAddress
                if (routes4.hasNext()) {
                    while (routes4.hasNext()) addRoute(builder, routes4.next())
                } else if (hasIpv4Address) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val routes6 = options.inet6RouteAddress
                if (routes6.hasNext()) {
                    while (routes6.hasNext()) addRoute(builder, routes6.next())
                } else if (hasIpv6Address) {
                    builder.addRoute("::", 0)
                }

                val excluded4 = options.inet4RouteExcludeAddress
                while (excluded4.hasNext()) builder.excludeRoute(toIpPrefix(excluded4.next()))

                val excluded6 = options.inet6RouteExcludeAddress
                while (excluded6.hasNext()) builder.excludeRoute(toIpPrefix(excluded6.next()))
            } else {
                val routes4 = options.inet4RouteRange
                while (routes4.hasNext()) {
                    val route = routes4.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val routes6 = options.inet6RouteRange
                while (routes6.hasNext()) {
                    val route = routes6.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            }
        }

        val descriptor = builder.establish()
            ?: error("Android не смог создать VPN-интерфейс")
        onTunCreated(descriptor)
        return descriptor.fd
    }

    private fun addRoute(builder: VpnService.Builder, route: RoutePrefix) {
        builder.addRoute(route.address(), route.prefix())
    }

    private fun toIpPrefix(route: RoutePrefix): android.net.IpPrefix {
        return android.net.IpPrefix(
            java.net.InetAddress.getByName(route.address()),
            route.prefix()
        )
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner = error("Определение владельца соединения пока не поддерживается")

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        interfaceListener = listener
        if (defaultNetworkCallback != null) {
            updateDefaultInterface(connectivity.activeNetwork)
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshDefaultInterface()
            }

            // Android can deliver onAvailable before LinkProperties are ready.
            // Re-check when the interface name, addresses or routes arrive;
            // otherwise sing-box may temporarily receive an empty interface.
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                refreshDefaultInterface()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties
            ) {
                refreshDefaultInterface()
            }

            override fun onLost(network: Network) {
                // Do not rely on Context.getMainExecutor(): it is API 28+,
                // while this application supports Android 7.0 (API 24).
                refreshDefaultInterface()
            }
        }
        defaultNetworkCallback = callback
        connectivity.registerDefaultNetworkCallback(callback)
        refreshDefaultInterface()
    }

    /**
     * Always resolve the current default network instead of trusting a callback's
     * Network argument. A delayed callback for a previous Wi-Fi/cellular network
     * must not overwrite the interface selected after a handover.
     */
    private fun refreshDefaultInterface() {
        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        updateDefaultInterface(active)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        defaultNetworkCallback = null
        interfaceListener = null
        lastUnderlyingNetwork = null
        lastInterfaceSnapshot = null
        hasReportedNoInterface = false
    }

    private fun updateDefaultInterface(network: Network?) {
        val listener = interfaceListener ?: return
        val selectedNetwork = resolveUnderlyingNetwork(network)
        if (selectedNetwork == null) {
            if (lastUnderlyingNetwork != null) {
                runCatching { vpnService.setUnderlyingNetworks(emptyArray()) }
                    .onFailure { Log.w(TAG, "Не удалось обновить базовую сеть VPN", it) }
                lastUnderlyingNetwork = null
            }
            notifyNoDefaultInterfaceIfChanged(listener)
            return
        }

        // Update Android only when the physical underlay actually changes.
        // Connectivity callbacks can arrive in bursts during Wi-Fi/mobile handover.
        if (lastUnderlyingNetwork != selectedNetwork) {
            runCatching { vpnService.setUnderlyingNetworks(arrayOf(selectedNetwork)) }
                .onSuccess { lastUnderlyingNetwork = selectedNetwork }
                .onFailure { Log.w(TAG, "Не удалось назначить базовую сеть VPN", it) }
        }

        val properties = runCatching { connectivity.getLinkProperties(selectedNetwork) }.getOrNull()
        val name = properties?.interfaceName
        if (name.isNullOrBlank()) {
            notifyNoDefaultInterfaceIfChanged(listener)
            return
        }
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val caps = runCatching { connectivity.getNetworkCapabilities(selectedNetwork) }.getOrNull()
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val snapshot = InterfaceSnapshot(name, index, metered)
        if (snapshot != lastInterfaceSnapshot || hasReportedNoInterface) {
            runCatching { listener.updateDefaultInterface(name, index, metered, false) }
                .onSuccess {
                    lastInterfaceSnapshot = snapshot
                    hasReportedNoInterface = false
                }
                .onFailure { Log.w(TAG, "Не удалось передать sing-box сведения о сети", it) }
        }
    }

    /**
     * When Android reports the VPN itself as the active network, select a
     * physical Internet-capable network deterministically instead of taking
     * the first entry in allNetworks (whose order is unspecified).
     */
    private fun resolveUnderlyingNetwork(active: Network?): Network? {
        fun isUsablePhysicalNetwork(network: Network?): Boolean {
            if (network == null) return false
            val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        // If Android exposes a physical default network, it is authoritative.
        // Do not choose another simultaneously-connected transport just because
        // it happens to be Wi-Fi (for example, when mobile data is the default).
        if (isUsablePhysicalNetwork(active)) return active

        // When the active network is this VPN, prefer the underlay Android has
        // already associated with this VpnService. allNetworks has no ordering
        // guarantee and can contain both Wi-Fi and cellular at the same time.
        val configuredUnderlay = runCatching {
            vpnService.underlyingNetworks?.firstOrNull()
        }.getOrNull()
        if (isUsablePhysicalNetwork(configuredUnderlay)) return configuredUnderlay

        // The configured underlay may have just disappeared. Fall back to the
        // best remaining physical network, preferring validated and unsuspended
        // connections before transport type.
        return connectivity.allNetworks.mapNotNull { candidate ->
            val caps = runCatching { connectivity.getNetworkCapabilities(candidate) }.getOrNull()
                ?: return@mapNotNull null
            if (!isUsablePhysicalNetwork(candidate)) return@mapNotNull null

            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val notSuspended = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            } else {
                true
            }
            val transportPriority = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 0
                else -> -1
            }
            Triple(candidate, validated to notSuspended, transportPriority)
        }.sortedWith(
            compareByDescending<Triple<Network, Pair<Boolean, Boolean>, Int>> { it.second.first }
                .thenByDescending { it.second.second }
                .thenByDescending { it.third }
        ).firstOrNull()?.first
    }

    private fun notifyNoDefaultInterfaceIfChanged(listener: InterfaceUpdateListener) {
        if (hasReportedNoInterface && lastInterfaceSnapshot == null) return
        runCatching { listener.updateDefaultInterface("", -1, false, false) }
            .onSuccess {
                lastInterfaceSnapshot = null
                hasReportedNoInterface = true
            }
            .onFailure { Log.w(TAG, "Не удалось сообщить sing-box об отсутствии сети", it) }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val items = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        for (network in connectivity.allNetworks) {
            val properties = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val name = properties.interfaceName ?: continue
            val ni = runCatching { JavaNetworkInterface.getByName(name) }.getOrNull() ?: continue
            val item = io.nekohasekai.libbox.NetworkInterface()
            item.name = name
            item.index = ni.index
            item.dnsServer = StringArray(properties.dnsServers.mapNotNull { it.hostAddress })
            item.gateway = StringArray(properties.routes.filter { it.destination.prefixLength == 0 }.mapNotNull { it.gateway }.filterNot { it.isAnyLocalAddress }.mapNotNull { it.hostAddress })
            item.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            item.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            item.addresses = StringArray(ni.interfaceAddresses.map { address ->
                val ip = if (address.address is Inet6Address) address.address.hostAddress.substringBefore('%') else address.address.hostAddress
                ip + "/" + address.networkPrefixLength
            })
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (ni.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (ni.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            item.flags = flags
            item.mtu = runCatching { ni.mtu }.getOrDefault(0)
            items.add(item)
        }
        val iterator = items.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
        }
    }

    private class StringArray(private val values: List<String>) : StringIterator {
        private val iterator = values.iterator()
        override fun len(): Int = values.size
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() = Unit

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() = Unit

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = error("Системная оболочка не поддерживается")

    override fun readSystemSSHHostKey(): String = error("SSH не поддерживается")

    override fun lookupSFTPServer(): String = error("SFTP не поддерживается")

    override fun tailscaleHostname(): String = "Astra VPN"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        error("Системный bridge не поддерживается")

    override fun lookupUser(username: String?): PlatformUser =
        error("Поиск системных пользователей не поддерживается")

    override fun registerMyInterface(name: String?) = Unit

    override fun sendNotification(notification: Notification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit
}
