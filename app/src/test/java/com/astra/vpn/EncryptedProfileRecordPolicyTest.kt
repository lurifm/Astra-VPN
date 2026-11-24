package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedProfileRecordPolicyTest {
    @Test
    fun everyStandaloneEncryptedRuntimeReferencePreventsKeyRegeneration() {
        val encryptedKeys = setOf(
            "recovery_profile_encrypted",
            "bank_pause_profile_encrypted",
            "active_tunnel_profile_encrypted",
            "selected_profile_encrypted"
        )

        encryptedKeys.forEach { key ->
            assertTrue("Expected $key to protect the existing Keystore key",
                EncryptedProfileRecordPolicy.hasEncryptedRecords(setOf(key)))
        }
    }

    @Test
    fun profileAndSubscriptionCiphertextPreventKeyRegeneration() {
        val encryptedKeys = setOf(
            "profile_123",
            "subscription_profile_source_0",
            "subscription_source_source"
        )

        encryptedKeys.forEach { key ->
            assertTrue("Expected $key to protect the existing Keystore key",
                EncryptedProfileRecordPolicy.hasEncryptedRecords(setOf(key)))
        }
    }

    @Test
    fun metadataAndTimestampAloneDoNotCountAsCiphertext() {
        val nonEncryptedKeys = setOf(
            "bank_pause_at",
            "subscription_meta_source",
            "subscription_custom_title_source",
            "favorite_servers"
        )

        assertFalse(EncryptedProfileRecordPolicy.hasEncryptedRecords(nonEncryptedKeys))
        assertFalse(EncryptedProfileRecordPolicy.hasEncryptedRecords(emptySet()))
    }
}
