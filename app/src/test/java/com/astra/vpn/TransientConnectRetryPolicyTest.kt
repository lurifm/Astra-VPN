package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransientConnectRetryPolicyTest {
    @Test fun schedulesTwoBoundedRetries() {
        assertEquals(3_000L, TransientConnectRetryPolicy.delayForAttempt(0))
        assertEquals(8_000L, TransientConnectRetryPolicy.delayForAttempt(1))
    }

    @Test fun refusesThirdRetry() {
        assertNull(TransientConnectRetryPolicy.delayForAttempt(2))
    }

    @Test fun negativeAttemptUsesFirstDelay() {
        assertEquals(3_000L, TransientConnectRetryPolicy.delayForAttempt(-1))
    }
}
