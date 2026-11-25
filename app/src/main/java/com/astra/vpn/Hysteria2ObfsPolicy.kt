package com.astra.vpn

/** Rejects Hysteria2 obfuscation options that would otherwise be silently ignored. */
internal object Hysteria2ObfsPolicy {
    fun requiresPassword(type: String?): Boolean =
        type?.equals("salamander", ignoreCase = true) == true

    fun isValid(type: String?, password: String?): Boolean {
        if (type.isNullOrBlank()) return password.isNullOrBlank()
        return requiresPassword(type) && !password.isNullOrBlank()
    }
}
