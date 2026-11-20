package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionProfileKeyTest {
    @Test
    fun sameSourcePositionAndLinkProduceSameStorageKey() {
        val first = SubscriptionProfileKey.storageKey("source-hash", 0, "vless://secret@host:443#Node")
        val second = SubscriptionProfileKey.storageKey("source-hash", 0, "vless://secret@host:443#Node")
        assertEquals(first, second)
    }

    @Test
    fun providerOrderIsEncodedInKeyAndSortsNumerically() {
        val first = SubscriptionProfileKey.storageKey("source-hash", 2, "vless://id@three:443")
        val second = SubscriptionProfileKey.storageKey("source-hash", 10, "vless://id@ten:443")
        assertTrue(first < second)
    }

    @Test
    fun differentLinksHaveDifferentKeysAndCredentialsAreNotExposed() {
        val link = "vless://user:private-token@host.example:443#Node"
        val key = SubscriptionProfileKey.storageKey("source-hash", 0, link)
        assertNotEquals(key, SubscriptionProfileKey.storageKey("source-hash", 0, link + "?x=1"))
        assertTrue(!key.contains("private-token"))
        assertTrue(!key.contains("host.example"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativePosition() {
        SubscriptionProfileKey.storageKey("source-hash", -1, "vless://id@host:443")
    }
}
