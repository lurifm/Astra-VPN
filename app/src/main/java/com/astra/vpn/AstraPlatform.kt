package com.astra.vpn

import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
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
    private val onTunCreated: (ParcelFileDescriptor) -> Boolean,
    private val protectSockets: Boolean = true,
    initialPreferredPhysicalNetwork: Network? = null,
    private val manageVpnUnderlyingNetworks: Boolean = true
) : PlatformInterface {

    @Volatile private var preferredPhysicalNetwork: Network? = initialPreferredPhysicalNetwork
    private val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)

    /** Change the preferred underlay for sockets opened after a network handover. */
    fun updatePreferredPhysicalNetwork(network: Network) {
        preferredPhysicalNetwork = network
    }

    /** Clear a lost underlay only if it is still the preferred one. */
    fun clearPreferredPhysicalNetwork(network: Network) {
        if (preferredPhysicalNetwork == network) preferredPhysicalNetwork = null
    }

    /** Select the best remaining physical network after the preferred one is lost. */
    fun refreshPreferredPhysicalNetwork(): Network? {
        // Clear a lost/stale preference even when no replacement is currently
        // usable. Otherwise a later caller can mistake the old Network handle
        // for the current underlay after a prolonged outage.
        val selected = probeNetworkCandidates().firstOrNull()
        preferredPhysicalNetwork = selected
        return selected
    }
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var interfaceListener: InterfaceUpdateListener? = null
    private var lastUnderlyingNetwork: Network? = null
    private var lastInterfaceSnapshot: InterfaceSnapshot? = null
    private var hasReportedNoInterface = false
    private val underlayRetryHandler = Handler(Looper.getMainLooper())
    private var underlayRetryAttempt = 0
    private var underlayRetryRunnable: Runnable? = null
    private var interfaceRetryAttempt = 0
    private var interfaceRetryRunnable: Runnable? = null

    private data class InterfaceSnapshot(
        // Include the Android Network handle, not just the interface name:
        // Wi-Fi can disconnect and reconnect with the same wlan interface,
        // while its Network identity and routing underneath have changed.
        val network: Network,
        val name: String,
        val index: Int,
        val metered: Boolean
    )

    override fun useProcFS(): Boolean = false

    companion object {
        private const val TAG = "AstraPlatform"

        // Shared only inside the main app process. MainActivity uses the
        // underlay selected by the active VpnService instead of guessing from
        // allNetworks while Android reports the VPN as active.
        @Volatile
        var currentPhysicalNetwork: Network? = null
            private set

        // Blocked status is not exposed by NetworkCapabilities. Keep one
        // process-wide snapshot so the VPN adapter and Activity use the same
        // decision when selecting or reporting a physical underlay.
        private val blockedPhysicalNetworkHandles =
            java.util.Collections.synchronizedSet(mutableSetOf<Network>())

        fun isPhysicalNetworkBlocked(network: Network): Boolean =
            blockedPhysicalNetworkHandles.contains(network)

        fun setPhysicalNetworkBlocked(network: Network, blocked: Boolean) {
            if (blocked) blockedPhysicalNetworkHandles.add(network)
            else blockedPhysicalNetworkHandles.remove(network)
        }
    }

    // The latency runner is a separate process but shares the app UID with the
    // active VPN. Its sockets must be explicitly bound to the physical underlay;
    // otherwise Android may route the probes back into Astra's own TUN.
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (protectSockets) {
            check(vpnService.protect(fd)) { "Не удалось исключить сокет sing-box из VPN-туннеля" }
        } else {
            // A network can disappear between Activity's selection and libbox
            // opening its next probe socket. Try every currently usable physical
            // network rather than failing the entire URL-test on a stale handle.
            val candidates = probeNetworkCandidates()
            var bound = false
            var lastFailure: Exception? = null
            for (physicalNetwork in candidates) {
                try {
                    ParcelFileDescriptor.fromFd(fd).use { socket ->
                        physicalNetwork.bindSocket(socket.fileDescriptor)
                    }
                    bound = true
                    break
                } catch (failure: Exception) {
                    lastFailure = failure
                    Log.w(TAG, "Не удалось привязать проверочный сокет к выбранной сети")
                }
            }
            if (!bound) {
                throw IllegalStateException(
                    "Не удалось привязать проверочный сокет к физической сети",
                    lastFailure
                )
            }
        }
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
                // Package names reveal the user's selected-app set; never write them to logcat.
                Log.w(
                    TAG,
                    "Не удалось применить одно из правил маршрутизации приложений (" +
                        error.javaClass.simpleName + ")"
                )
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
        val accepted = try {
            onTunCreated(descriptor)
        } catch (error: Throwable) {
            // The callback owns the descriptor only after explicitly accepting it.
            runCatching { descriptor.close() }
                .onFailure { Log.w(TAG, "Rejected TUN descriptor close failed (" + it.javaClass.simpleName + ")") }
            throw error
        }
        if (!accepted) {
            runCatching { descriptor.close() }
                .onFailure { Log.w(TAG, "Unclaimed TUN descriptor close failed (" + it.javaClass.simpleName + ")") }
            error("VPN service rejected a TUN descriptor from an obsolete lifecycle")
        }
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

    @Synchronized
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

            // Blocked status is independent of capabilities: a blocked network
            // can continue to advertise INTERNET and VALIDATED.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                setPhysicalNetworkBlocked(network, blocked)
                refreshDefaultInterface()
            }

            override fun onLost(network: Network) {
                setPhysicalNetworkBlocked(network, false)
                // Do not rely on Context.getMainExecutor(): it is API 28+,
                // while this application supports Android 7.0 (API 24).
                refreshDefaultInterface()
            }
        }
        defaultNetworkCallback = callback
        // Track every Internet-capable physical network, not only Android's
        // current default. During Wi-Fi/cellular handover an alternate network
        // may remain blocked while still advertising INTERNET and VALIDATED.
        // Its blocked-status callback must update the shared registry before
        // resolveUnderlyingNetwork/probeNetworkCandidates can select it.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        refreshDefaultInterface()
    }

    /**
     * Always resolve the current default network instead of trusting a callback's
     * Network argument. A delayed callback for a previous Wi-Fi/cellular network
     * must not overwrite the interface selected after a handover.
     */
    @Synchronized
    private fun refreshDefaultInterface() {
        // Read activeNetwork and update the snapshot under the same monitor.
        // This prevents an older callback from applying a stale network after
        // a newer callback has already processed a handover.
        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        updateDefaultInterface(active)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // Detach state before unregistering. A callback already queued by
        // Android may still run, but it will see a null listener and do nothing.
        val callback = synchronized(this) {
            val current = defaultNetworkCallback
            defaultNetworkCallback = null
            interfaceListener = null
            lastUnderlyingNetwork = null
            cancelUnderlayRetryLocked(resetAttempts = true)
            cancelInterfaceRetryLocked(resetAttempts = true)
            if (protectSockets) currentPhysicalNetwork = null
            lastInterfaceSnapshot = null
            hasReportedNoInterface = false
            current
        }
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
    }

    @Synchronized
    private fun updateDefaultInterface(network: Network?) {
        val listener = interfaceListener ?: return
        val selectedNetwork = resolveUnderlyingNetwork(network)
        if (selectedNetwork == null) {
            if (manageVpnUnderlyingNetworks && lastUnderlyingNetwork != null) {
                val cleared = runCatching { vpnService.setUnderlyingNetworks(emptyArray()) }
                    .onFailure { Log.w(TAG, "Не удалось очистить базовую сеть VPN", it) }
                    .isSuccess
                // Retain the previous handle after a failed Android update so a
                // later callback can retry instead of pretending it was cleared.
                if (cleared) {
                    lastUnderlyingNetwork = null
                    cancelUnderlayRetryLocked(resetAttempts = true)
                } else {
                    scheduleUnderlayRetryLocked()
                }
            }
            if (protectSockets) currentPhysicalNetwork = null
            notifyNoDefaultInterfaceIfChanged(listener)
            return
        }

        // Android's VPN underlay and sing-box's interface metadata must move
        // together. Do not publish the new route to probes/libbox if Android
        // rejected setUnderlyingNetworks(); the next network callback retries.
        val updateRequired = manageVpnUnderlyingNetworks && lastUnderlyingNetwork != selectedNetwork
        val updateSucceeded = if (updateRequired) {
            runCatching { vpnService.setUnderlyingNetworks(arrayOf(selectedNetwork)) }
                .onSuccess {
                    lastUnderlyingNetwork = selectedNetwork
                    cancelUnderlayRetryLocked(resetAttempts = true)
                }
                .onFailure {
                    Log.w(TAG, "Не удалось назначить базовую сеть VPN", it)
                    scheduleUnderlayRetryLocked()
                }
                .isSuccess
        } else {
            true
        }
        if (!NetworkRecoveryPolicy.shouldPublishUnderlay(
                managesAndroidUnderlay = manageVpnUnderlyingNetworks,
                updateRequired = updateRequired,
                updateSucceeded = updateSucceeded
            )
        ) {
            if (protectSockets) currentPhysicalNetwork = null
            notifyNoDefaultInterfaceIfChanged(listener)
            return
        }

        // Any accepted state supersedes a pending retry (including an unchanged
        // route); reset the retry budget for the next independent transition.
        cancelUnderlayRetryLocked(resetAttempts = true)

        // Publish only after Android accepted the same physical underlay.
        if (protectSockets) currentPhysicalNetwork = selectedNetwork

        val properties = runCatching { connectivity.getLinkProperties(selectedNetwork) }.getOrNull()
        val name = properties?.interfaceName
        if (name.isNullOrBlank()) {
            // A Network can become available before Android has populated its
            // LinkProperties. Do not wait indefinitely for another callback:
            // re-read the current default route using the bounded retry policy.
            notifyNoDefaultInterfaceIfChanged(listener)
            scheduleInterfaceRetryLocked()
            return
        }
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val caps = runCatching { connectivity.getNetworkCapabilities(selectedNetwork) }.getOrNull()
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val snapshot = InterfaceSnapshot(selectedNetwork, name, index, metered)
        if (snapshot != lastInterfaceSnapshot || hasReportedNoInterface) {
            runCatching { listener.updateDefaultInterface(name, index, metered, false) }
                .onSuccess {
                    lastInterfaceSnapshot = snapshot
                    hasReportedNoInterface = false
                }
                .onFailure {
                    Log.w(TAG, "Не удалось передать sing-box сведения о сети", it)
                    scheduleInterfaceRetryLocked()
                }
                .onSuccess { cancelInterfaceRetryLocked(resetAttempts = true) }
        }
    }

    /**
     * Returns live physical networks suitable for an outbound latency socket.
     * A preferred underlay is only a preference: Android may invalidate it
     * while the URL-test service is starting or while a mobile/Wi-Fi handover
     * is in progress. Validated, unsuspended networks are tried first.
     */
    private fun probeNetworkCandidates(): List<Network> {
        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        val preferred = preferredPhysicalNetwork
        return (listOfNotNull(preferred, active) + connectivity.allNetworks)
            .distinct()
            .mapNotNull { network ->
                val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                    ?: return@mapNotNull null
                val notSuspended = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                } else {
                    true
                }
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!NetworkRecoveryPolicy.isProbeSocketNetworkEligible(
                        isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        isBlocked = isPhysicalNetworkBlocked(network),
                        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        isValidated = validated,
                        isNotSuspended = notSuspended
                    )
                ) return@mapNotNull null
                val isPreferred = network == preferred
                val isActive = network == active
                val transportPriority = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 0
                    else -> -1
                }
                ProbeNetworkCandidate(network, validated, notSuspended, isPreferred, isActive, transportPriority)
            }
            .filter { it.notSuspended }
            .sortedWith(
                compareByDescending<ProbeNetworkCandidate> { it.validated }
                    .thenByDescending { it.preferred }
                    .thenByDescending { it.active }
                    .thenByDescending { it.transportPriority }
            )
            .map { it.network }
    }

    private data class ProbeNetworkCandidate(
        val network: Network,
        val validated: Boolean,
        val notSuspended: Boolean,
        val preferred: Boolean,
        val active: Boolean,
        val transportPriority: Int
    )

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
            if (!NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(
                    isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    isBlocked = isPhysicalNetworkBlocked(network),
                    hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    isNotSuspended = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                    } else {
                        true
                    }
                )
            ) return false

            // Android can keep a physical Network object around while its
            // radio is suspended during handover or power management. Do not
            // pin the VPN to that network merely because it still advertises
            // INTERNET; wait for an unsuspended default/underlay instead.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            ) return false

            return true
        }

        // URL-test receives the exact physical network selected by the main
        // process. Keep interface metadata and socket binding on that same route.
        val preferred = preferredPhysicalNetwork
        if (isUsablePhysicalNetwork(preferred)) return preferred

        // If Android exposes a physical default network, it is authoritative.
        // Do not choose another simultaneously-connected transport just because
        // it happens to be Wi-Fi (for example, when mobile data is the default).
        if (isUsablePhysicalNetwork(active)) return active

        // When the active network is this VPN, prefer the underlay Android has
        // already associated with this VpnService. allNetworks has no ordering
        // guarantee and can contain both Wi-Fi and cellular at the same time.
        // Keep our own last successfully assigned underlay. VpnService
        // exposes setUnderlyingNetworks(), but has no public getter on all
        // supported Android SDK surfaces.
        val configuredUnderlay = lastUnderlyingNetwork
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

    /** Retry transient Android underlay assignment failures with bounded backoff.
     * Each retry re-reads activeNetwork; it never replays a stale Network handle.
     */
    @Synchronized
    private fun scheduleUnderlayRetryLocked() {
        if (underlayRetryRunnable != null) return
        val delay = NetworkRecoveryPolicy.retryDelayMs(underlayRetryAttempt) ?: return
        underlayRetryAttempt++
        val retry = Runnable {
            synchronized(this) {
                underlayRetryRunnable = null
                if (interfaceListener == null) return@synchronized
                refreshDefaultInterface()
            }
        }
        underlayRetryRunnable = retry
        underlayRetryHandler.postDelayed(retry, delay)
    }

    private fun scheduleInterfaceRetryLocked() {
        if (interfaceRetryRunnable != null) return
        val delay = NetworkRecoveryPolicy.retryDelayMs(interfaceRetryAttempt) ?: return
        interfaceRetryAttempt++
        val retry = Runnable {
            synchronized(this) {
                interfaceRetryRunnable = null
                if (interfaceListener == null) return@synchronized
                // Re-read the current network and its LinkProperties; never replay
                // a stale interface snapshot captured by the failed callback.
                refreshDefaultInterface()
            }
        }
        interfaceRetryRunnable = retry
        underlayRetryHandler.postDelayed(retry, delay)
    }

    @Synchronized
    private fun cancelUnderlayRetryLocked(resetAttempts: Boolean) {
        underlayRetryRunnable?.let(underlayRetryHandler::removeCallbacks)
        underlayRetryRunnable = null
        if (resetAttempts) underlayRetryAttempt = 0
    }

    @Synchronized
    private fun cancelInterfaceRetryLocked(resetAttempts: Boolean) {
        interfaceRetryRunnable?.let(underlayRetryHandler::removeCallbacks)
        interfaceRetryRunnable = null
        if (resetAttempts) interfaceRetryAttempt = 0
    }

    private fun notifyNoDefaultInterfaceIfChanged(listener: InterfaceUpdateListener) {
        if (hasReportedNoInterface && lastInterfaceSnapshot == null) return
        runCatching { listener.updateDefaultInterface("", -1, false, false) }
            .onSuccess {
                lastInterfaceSnapshot = null
                hasReportedNoInterface = true
                cancelInterfaceRetryLocked(resetAttempts = true)
            }
            .onFailure {
                Log.w(TAG, "Не удалось сообщить sing-box об отсутствии сети", it)
                scheduleInterfaceRetryLocked()
            }
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
