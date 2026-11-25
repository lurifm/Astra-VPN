package com.astra.vpn

/** Prevents unknown VLESS security modes from silently becoming plaintext connections. */
internal object VlessSecurityPolicy {
    private val supportedModes = setOf("", "none", "tls", "reality")

    fun isSupported(mode: String?): Boolean =
        mode?.lowercase()?.let { it in supportedModes } ?: true
}
