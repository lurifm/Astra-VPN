package com.astra.vpn

/** Hard cap prevents a small but adversarial subscription from creating unbounded parser work. */
internal object SubscriptionImportPolicy {
    const val MAX_PROFILES = 1000

    fun isWithinProfileLimit(profileCount: Int): Boolean =
        profileCount in 1..MAX_PROFILES
}
