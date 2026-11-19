package com.astra.vpn

import android.content.Context
import android.net.Network
import java.net.ServerSocket

/**
 * Allocates loopback proxy ports, builds the isolated engine config and starts
 * the throughput service. The temporary ServerSockets are closed before
 * libbox binds; the service performs a bounded readiness check before probing.
 */
internal object ThroughputTestLauncher {
    fun start(context: Context, profileLinks: List<String>, physicalNetwork: Network): Boolean {
        val profiles = profileLinks.map(String::trim).filter(String::isNotEmpty).distinct()
        if (profiles.isEmpty() || profiles.size > MAX_PROFILES) return false

        return runCatching {
            val usedPorts = mutableSetOf<Int>()
            val targets = profiles.map { link ->
                var port: Int
                var attempts = 0
                do {
                    check(attempts++ < PORT_ALLOCATION_ATTEMPTS) { "Не удалось выделить локальный порт" }
                    port = ServerSocket(0).use { it.localPort }
                } while (port in usedPorts)
                usedPorts += port
                SingBoxThroughputTestConfig.Target(link, port)
            }
            val config = SingBoxThroughputTestConfig.build(targets)
            AstraThroughputTestService.start(
                context = context,
                config = config.json,
                portsByTag = config.portByOutboundTag,
                network = physicalNetwork
            )
            true
        }.getOrDefault(false)
    }

    private const val MAX_PROFILES = 64
    private const val PORT_ALLOCATION_ATTEMPTS = 16
}
