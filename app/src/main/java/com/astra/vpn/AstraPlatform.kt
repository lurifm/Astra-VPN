package com.astra.vpn

import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.OsConstants
import android.os.ParcelFileDescriptor
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

    override fun useProcFS(): Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(vpnService.protect(fd)) { "Не удалось исключить сокет sing-box из VPN-туннеля" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(vpnService) == null) { "Не выдано разрешение Android VPN" }

        val builder = vpnService.Builder()
            .setSession("Astra VPN")
            .setMtu(options.mtu)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val ipv4 = options.inet4Address
        while (ipv4.hasNext()) {
            val address = ipv4.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val ipv6 = options.inet6Address
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
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val routes6 = options.inet6RouteAddress
                if (routes6.hasNext()) {
                    while (routes6.hasNext()) addRoute(builder, routes6.next())
                } else if (options.inet6Address.hasNext()) {
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

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
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
                updateDefaultInterface(network)
            }
            override fun onLost(network: Network) {
                val active = connectivity.activeNetwork
                if (active == null) listener.updateDefaultInterface("", -1, false, false)
                else updateDefaultInterface(active)
            }
        }
        defaultNetworkCallback = callback
        connectivity.registerDefaultNetworkCallback(callback)
        updateDefaultInterface(connectivity.activeNetwork)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        defaultNetworkCallback = null
        interfaceListener = null
    }

    private fun updateDefaultInterface(network: Network?) {
        val listener = interfaceListener ?: return
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val initialCaps = connectivity.getNetworkCapabilities(network)
        val selectedNetwork = if (initialCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            connectivity.allNetworks.firstOrNull { candidate ->
                val caps = connectivity.getNetworkCapabilities(candidate)
                caps != null &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } else {
            network
        } ?: run {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val properties = connectivity.getLinkProperties(selectedNetwork) ?: return
        val name = properties.interfaceName ?: return
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val caps = connectivity.getNetworkCapabilities(selectedNetwork)
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        listener.updateDefaultInterface(name, index, metered, false)
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
