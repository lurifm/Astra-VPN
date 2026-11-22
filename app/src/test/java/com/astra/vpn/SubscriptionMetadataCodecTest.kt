package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionMetadataCodecTest {
    @Test fun roundTripsAllFields() {
        val metadata = SubscriptionMetadata(
            uploadBytes = 12L,
            downloadBytes = 34L,
            totalBytes = 100L,
            expiresAtEpochSeconds = 1900000000L,
            updateIntervalHours = 12
        )
        assertEquals(
            StoredSubscriptionUsage(12L, 34L, 100L, 1900000000L, 12),
            SubscriptionMetadataCodec.decode(SubscriptionMetadataCodec.encode(metadata))
        )
    }

    @Test fun missingAndMalformedValuesBecomeNull() {
        assertEquals(StoredSubscriptionUsage(), SubscriptionMetadataCodec.decode(null))
        assertEquals(
            StoredSubscriptionUsage(uploadBytes = 0L, totalBytes = 500L),
            SubscriptionMetadataCodec.decode("0,not-a-number,500,-1")
        )
        assertEquals(
            StoredSubscriptionUsage(),
            SubscriptionMetadataCodec.decode("x,-2,,9223372036854775807")
        )
    }

    @Test fun encodeRejectsNegativeCountersAndInvalidExpiry() {
        val metadata = SubscriptionMetadata(
            uploadBytes = -1L,
            downloadBytes = 2L,
            totalBytes = -3L,
            expiresAtEpochSeconds = Long.MAX_VALUE
        )
        assertEquals(",2,,,", SubscriptionMetadataCodec.encode(metadata))
    }

    @Test fun truncatedMetadataKeepsAvailableCounters() {
        assertEquals(
            StoredSubscriptionUsage(uploadBytes = 25L, downloadBytes = 75L),
            SubscriptionMetadataCodec.decode("25,75")
        )
    }

    @Test fun expiryMustBeWithinSupportedCalendarRange() {
        assertEquals(
            StoredSubscriptionUsage(expiresAtEpochSeconds = 253402300799L),
            SubscriptionMetadataCodec.decode(",,,253402300799")
        )
        assertEquals(
            StoredSubscriptionUsage(),
            SubscriptionMetadataCodec.decode(",,,0")
        )
    }

    @Test fun updateIntervalRoundTripsAndRejectsOutOfRangeValues() {
        assertEquals(
            StoredSubscriptionUsage(updateIntervalHours = 24),
            SubscriptionMetadataCodec.decode(",,,,24")
        )
        assertEquals(StoredSubscriptionUsage(), SubscriptionMetadataCodec.decode(",,,,0"))
        assertEquals(StoredSubscriptionUsage(), SubscriptionMetadataCodec.decode(",,,,721"))
    }

    @Test fun oldEmptyMetadataRepresentationRemainsReadable() {
        assertEquals(StoredSubscriptionUsage(), SubscriptionMetadataCodec.decode(",,,"))
    }
}
