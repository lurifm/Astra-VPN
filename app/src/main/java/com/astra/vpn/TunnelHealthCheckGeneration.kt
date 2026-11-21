package com.astra.vpn

/**
 * Generation token for end-to-end health probes.
 *
 * A VPN profile reload may reuse Android's Network object, so comparing Network
 * identity is not enough to establish that a probe still belongs to the active
 * sing-box configuration. Advance the token whenever the tunnel lifecycle changes.
 */
internal class TunnelHealthCheckGeneration {
    private var value = 0L

    @Synchronized
    fun advance(): Long {
        value = if (value == Long.MAX_VALUE) 0L else value + 1L
        return value
    }

    @Synchronized
    fun current(): Long = value

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = value == candidate
}
