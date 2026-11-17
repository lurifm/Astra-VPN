package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionMetadataCodecTest {
    @Test fun roundTripsAllFields() {
        val metadata = SubscriptionMetadata(
            uploadBytes = 12L,
            downloadBytes = 34L,
            totalBytes = 100L,
            expiresAtEpochSeconds = 1900000000L
        )
        assertEquals(
            StoredSubscriptionUsage(12L, 34L, 100L, 1900000000L),
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
        assertEquals(",2,,", SubscriptionMetadataCodec.encode(metadata))
    }

    @Test fun oldEmptyMetadataRepresentationRemainsReadable() {
        assertEquals(StoredSubscriptionUsage(), SubscriptionMetadataCodec.decode(",,,"))
    }
}
