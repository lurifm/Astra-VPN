package com.astra.vpn

import java.security.MessageDigest
import java.util.Locale

/** Stable non-reversible profile identifier; raw VPN URIs are never persisted. */
internal object ProfileIdentity {
    fun of(profile: String): String {
        if (profile.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(profile.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
