package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionMetadataTest {
    @Test fun parsesUsageHeadersCaseInsensitively() {
        val metadata = SubscriptionParser.parseMetadata(
            mapOf(
                "Subscription-UserInfo" to "upload=120; download=340; total=1000; expire=1900000000",
                "Profile-Title" to "My VPN"
            )
        )

        assertEquals("My VPN", metadata.title)
        assertEquals(120L, metadata.uploadBytes)
        assertEquals(340L, metadata.downloadBytes)
        assertEquals(1000L, metadata.totalBytes)
        assertEquals(1900000000L, metadata.expiresAtEpochSeconds)
    }

    @Test fun ignoresNegativeAndMalformedCounters() {
        val metadata = SubscriptionParser.parseMetadata(
            mapOf("subscription-userinfo" to "upload=-1; download=abc; total=500; expire=-20")
        )

        assertNull(metadata.uploadBytes)
        assertNull(metadata.downloadBytes)
        assertEquals(500L, metadata.totalBytes)
        assertNull(metadata.expiresAtEpochSeconds)
    }

    @Test fun removesControlCharactersAndLimitsProviderTitle() {
        val metadata = SubscriptionParser.parseMetadata(
            mapOf("profile-title" to "  Safe\u0000 VPN  ")
        )
        assertEquals("Safe VPN", metadata.title)

        val longTitle = SubscriptionParser.parseMetadata(
            mapOf("profile-title" to "x".repeat(200))
        )
        assertEquals(120, longTitle.title?.length)
    }

    @Test fun ignoresExpiryOutsideSupportedDateRange() {
        val metadata = SubscriptionParser.parseMetadata(
            mapOf("subscription-userinfo" to "expire=9223372036854775807")
        )
        assertNull(metadata.expiresAtEpochSeconds)
    }

    @Test fun emptyHeadersProduceEmptyMetadata() {
        val metadata = SubscriptionParser.parseMetadata(emptyMap())
        assertNull(metadata.title)
        assertNull(metadata.uploadBytes)
        assertNull(metadata.downloadBytes)
        assertNull(metadata.totalBytes)
        assertNull(metadata.expiresAtEpochSeconds)
    }
}
