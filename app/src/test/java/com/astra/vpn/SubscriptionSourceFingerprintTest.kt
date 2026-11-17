package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SubscriptionSourceFingerprintTest {
    @Test
    fun fingerprintIsStableSha256Hex() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            SubscriptionSourceFingerprint.of("hello")
        )
    }

    @Test
    fun credentialsDoNotAppearInStoredFingerprint() {
        val source = "https://user:secret@example.com/sub?token=private"
        val fingerprint = SubscriptionSourceFingerprint.of(source)

        assertEquals(64, fingerprint.length)
        assertFalse(fingerprint.contains("secret"))
        assertFalse(fingerprint.contains("private"))
        assertNotEquals(source, fingerprint)
    }

    @Test
    fun legacyPlaintextValueIsConverted() {
        val legacySource = "https://example.com/sub?token=legacy-secret"

        assertEquals(
            SubscriptionSourceFingerprint.of(legacySource),
            SubscriptionSourceFingerprint.fromStoredValue(legacySource)
        )
    }

    @Test
    fun existingFingerprintIsNotHashedAgain() {
        val fingerprint = SubscriptionSourceFingerprint.of("https://example.com/sub")

        assertEquals(fingerprint, SubscriptionSourceFingerprint.fromStoredValue(fingerprint))
    }
}
