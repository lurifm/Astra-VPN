package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionImportPolicyTest {
    @Test fun acceptsNormalAndMaximumSizedSubscriptions() {
        assertTrue(SubscriptionImportPolicy.isWithinProfileLimit(1))
        assertTrue(SubscriptionImportPolicy.isWithinProfileLimit(1000))
    }

    @Test fun rejectsEmptyAndOversizedSubscriptions() {
        assertFalse(SubscriptionImportPolicy.isWithinProfileLimit(0))
        assertFalse(SubscriptionImportPolicy.isWithinProfileLimit(1001))
        assertFalse(SubscriptionImportPolicy.isWithinProfileLimit(-1))
    }
}
