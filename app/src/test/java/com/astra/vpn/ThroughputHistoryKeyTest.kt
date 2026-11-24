package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ThroughputHistoryKeyTest {
    @Test
    fun sameProfileHasIndependentKeysForDifferentPhysicalNetworks() {
        val profile = "vless://user@example.com:443?security=tls"
        val wifi = ThroughputHistoryKey.sampleKey(profile, 101L)
        val cellular = ThroughputHistoryKey.sampleKey(profile, 202L)

        assertNotEquals(wifi, cellular)
        assertEquals(wifi, ThroughputHistoryKey.sampleKey(profile, 101L))
        assertTrue(ThroughputHistoryKey.isSampleKey(wifi, 101L))
        assertTrue(ThroughputHistoryKey.isSampleKey(cellular, 202L))
        assertFalse(ThroughputHistoryKey.isSampleKey(wifi, 202L))
    }

    @Test
    fun persistedKeysDoNotContainProfileUri() {
        val profile = "vless://secret-user@example.com:443"
        val key = ThroughputHistoryKey.sampleKey(profile, 101L)

        assertFalse(key.contains("secret-user"))
        assertFalse(key.contains("example.com"))
    }

    @Test
    fun recognizesLegacyKeyAndBuildsMigratedNetworkSpecificKey() {
        val profile = "vless://legacy@example.com:443"
        val legacy = ThroughputHistoryKey.legacyKey(profile)
        val migrated = ThroughputHistoryKey.sampleKeyFromLegacy(legacy, 303L)

        assertTrue(ThroughputHistoryKey.isLegacyKey(legacy))
        assertFalse(ThroughputHistoryKey.isLegacyKey(migrated))
        assertTrue(ThroughputHistoryKey.isSampleKey(migrated, 303L))
    }

    @Test
    fun rejectsMalformedAndMismatchedNetworkKeys() {
        assertFalse(ThroughputHistoryKey.isLegacyKey("sample_not-a-hash"))
        val zero = "sample_" + "a".repeat(64) + "_0"
        val ten = "sample_" + "a".repeat(64) + "_10"
        assertFalse(ThroughputHistoryKey.isSampleKey(zero, 0L))
        assertFalse(ThroughputHistoryKey.isSampleKey(ten, 11L))
    }
}
