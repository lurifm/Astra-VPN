package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUsageCalculatorTest {
    @Test fun sumsUploadAndDownload() {
        assertEquals(450L, SubscriptionUsageCalculator.usedBytes(150L, 300L))
    }

    @Test fun missingAndNegativeCountersAreTreatedAsZero() {
        assertEquals(20L, SubscriptionUsageCalculator.usedBytes(-5L, 20L))
        assertEquals(0L, SubscriptionUsageCalculator.usedBytes(null, -1L))
    }

    @Test fun additionSaturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            SubscriptionUsageCalculator.usedBytes(Long.MAX_VALUE - 2L, 10L)
        )
    }

    @Test fun percentageIsClampedToValidRange() {
        assertEquals(50, SubscriptionUsageCalculator.percentage(50L, 100L))
        assertEquals(100, SubscriptionUsageCalculator.percentage(150L, 100L))
        assertEquals(0, SubscriptionUsageCalculator.percentage(-10L, 100L))
    }

    @Test fun percentageIsUnavailableWithoutPositiveLimit() {
        assertNull(SubscriptionUsageCalculator.percentage(10L, 0L))
        assertNull(SubscriptionUsageCalculator.percentage(10L, -1L))
    }
}
