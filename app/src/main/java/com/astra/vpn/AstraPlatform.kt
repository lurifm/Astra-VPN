package com.astra.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.TunOptions

/**
 * Android adapter used by libbox to create the system VPN TUN interface.
 */
class AstraPlatform(
    private val vpnService: VpnService,
    private val onTunCreated: (ParcelFileDescriptor) -> Unit
) : PlatformInterface {

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(vpnService.protect(fd)) { "Не удалось исключить сокет sing-box из VPN-туннеля" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(vpnService) == null) { "Не выдано разрешение Android VPN" }

        val builder = vpnService.Builder()
            .setSession("Astra VPN")
            .setMtu(options.mtu)

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

        val dns = options.dnsServerAddress
        while (dns.hasNext()) {
            builder.addDnsServer(dns.next())
        }

        if (options.autoRoute) {
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

        val descriptor = builder.establish()
            ?: error("Android не смог создать VPN-интерфейс")
        onTunCreated(descriptor)
        return descriptor.fd
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() = Unit
}
