package com.astra.vpn

import java.security.MessageDigest

/**
 * Deterministic SharedPreferences key for a profile inside a subscription.
 * The ordinal preserves provider order; the digest makes the key stable for
 * the same link and avoids placing credentials or the URI in the key itself.
 */
internal object SubscriptionProfileKey {
    fun storageKey(sourceId: String, index: Int, link: String): String {
        require(sourceId.isNotBlank()) { "Subscription source ID is empty" }
        require(index >= 0) { "Profile index cannot be negative" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(link.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "subscription_profile_${sourceId}_${index.toString().padStart(8, '0')}_$digest"
    }
}
