package com.astra.vpn

import java.security.MessageDigest

/**
 * Opaque SharedPreferences keys for throughput samples. The profile URI is
 * never stored; each physical Android underlay gets an independent entry.
 */
internal object ThroughputHistoryKey {
    private const val PREFIX = "sample_"
    private val legacyPattern = Regex("^sample_[0-9a-f]{64}$")
    private val samplePattern = Regex("^sample_[0-9a-f]{64}_[1-9][0-9]*$")

    fun legacyKey(profile: String): String = PREFIX + profileHash(profile)

    fun sampleKey(profile: String, networkHandle: Long): String {
        require(networkHandle > 0L)
        return legacyKey(profile) + "_" + networkHandle
    }

    fun sampleKeyFromLegacy(legacyKey: String, networkHandle: Long): String {
        require(isLegacyKey(legacyKey) && networkHandle > 0L)
        return legacyKey + "_" + networkHandle
    }

    fun isLegacyKey(value: String): Boolean = legacyPattern.matches(value)

    fun isSampleKey(value: String, networkHandle: Long): Boolean =
        networkHandle > 0L && samplePattern.matches(value) &&
            value.endsWith("_" + networkHandle)

    private fun profileHash(profile: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(profile.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
