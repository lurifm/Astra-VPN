package com.astra.vpn

/**
 * Identifies preferences that contain Android-Keystore ciphertext.
 *
 * If the Keystore alias disappears while any of these records remain, a new
 * key must not be generated: doing so would make the existing data unreadable.
 */
internal object EncryptedProfileRecordPolicy {
    fun hasEncryptedRecords(keys: Set<String>): Boolean = keys.any { key ->
        key == "recovery_profile_encrypted" ||
            key == "bank_pause_profile_encrypted" ||
            key == "active_tunnel_profile_encrypted" ||
            key == "selected_profile_encrypted" ||
            key == "throughput_session_profile_encrypted" ||
            key.startsWith("profile_") ||
            key.startsWith("subscription_profile_") ||
            key.startsWith("subscription_source_")
    }
}
