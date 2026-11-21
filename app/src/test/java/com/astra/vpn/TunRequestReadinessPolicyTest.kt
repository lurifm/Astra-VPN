package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunRequestReadinessPolicyTest {
    @Test fun currentRequestWithAcceptedConfigAndNewTunIsReady() {
        assertTrue(TunRequestReadinessPolicy.isReady(true, true, 12L, 12L))
    }

    @Test fun oldTunCannotSatisfyReplacementRequest() {
        assertFalse(TunRequestReadinessPolicy.isReady(true, true, 11L, 12L))
    }

    @Test fun tunBeforeConfigAcceptanceIsNotReady() {
        assertFalse(TunRequestReadinessPolicy.isReady(false, true, 12L, 12L))
    }

    @Test fun acceptedConfigWithoutTunIsNotReady() {
        assertFalse(TunRequestReadinessPolicy.isReady(true, false, 12L, 12L))
    }

    @Test fun missingRequestIdentityIsNotReady() {
        assertFalse(TunRequestReadinessPolicy.isReady(true, true, null, 12L))
    }
}
