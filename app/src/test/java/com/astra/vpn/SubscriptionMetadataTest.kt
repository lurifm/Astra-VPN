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

    @Test fun ignoresUnknownAndMalformedUsageFields() {
        val metadata = SubscriptionParser.parseMetadata(
            mapOf("subscription-userinfo" to "upload=15; unknown=999; download=oops; total=500")
        )
        assertEquals(15L, metadata.uploadBytes)
        assertNull(metadata.downloadBytes)
        assertEquals(500L, metadata.totalBytes)
        assertNull(metadata.expiresAtEpochSeconds)
    }

    @Test fun blankProviderTitleIsIgnored() {
        val metadata = SubscriptionParser.parseMetadata(
            mapOf("profile-title" to "   \t  ")
        )
        assertNull(metadata.title)
    }

    @Test fun parsesAndBoundsProviderRefreshInterval() {
        assertEquals(
            6,
            SubscriptionParser.parseMetadata(mapOf("profile-update-interval" to "6")).updateIntervalHours
        )
        assertEquals(
            720,
            SubscriptionParser.parseMetadata(mapOf("Profile-Update-Interval" to "720")).updateIntervalHours
        )
        assertNull(SubscriptionParser.parseMetadata(mapOf("profile-update-interval" to "0")).updateIntervalHours)
        assertNull(SubscriptionParser.parseMetadata(mapOf("profile-update-interval" to "721")).updateIntervalHours)
        assertNull(SubscriptionParser.parseMetadata(mapOf("profile-update-interval" to "hourly")).updateIntervalHours)
    }

    @Test fun emptyHeadersProduceEmptyMetadata() {
        val metadata = SubscriptionParser.parseMetadata(emptyMap())
        assertNull(metadata.title)
        assertNull(metadata.uploadBytes)
        assertNull(metadata.downloadBytes)
        assertNull(metadata.totalBytes)
        assertNull(metadata.expiresAtEpochSeconds)
        assertNull(metadata.updateIntervalHours)
    }

    @Test fun acceptsOnlyHttpsSubscriptionUrlsWithoutEmbeddedCredentials() {
        assertEquals(
            "https",
            SubscriptionParser.validateSubscriptionUrl("https://example.com/sub").protocol
        )
        assertRejectedUrl("http://example.com/sub")
        assertRejectedUrl("https://user:password@example.com/sub")
    }

    @Test fun rejectsSubscriptionUrlsWithInvalidPorts() {
        assertRejectedUrl("https://example.com:70000/sub")
    }

    @Test fun enforcesTwoMegabyteInputLimit() {
        SubscriptionParser.validateInputSize("x".repeat(2 * 1024 * 1024))
        try {
            SubscriptionParser.validateInputSize("x".repeat(2 * 1024 * 1024 + 1))
            org.junit.Assert.fail("Oversized subscription input must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Текст подписки слишком большой (максимум 2 МБ)", expected.message)
        }
    }

    private fun assertRejectedUrl(address: String) {
        try {
            SubscriptionParser.validateSubscriptionUrl(address)
            org.junit.Assert.fail("Subscription URL should have been rejected: $address")
        } catch (_: IllegalArgumentException) {
            // Expected: insecure or malformed URL was rejected before any request.
        }
    }
}
