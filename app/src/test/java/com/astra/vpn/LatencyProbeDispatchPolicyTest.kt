package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProbeDispatchPolicyTest {
    @Test fun dispatchesOnlyActiveIncompleteProbes() {
        assertTrue(LatencyProbeDispatchPolicy.shouldDispatch(false, false))
    }

    @Test fun skipsProbesAfterCancellation() {
        assertFalse(LatencyProbeDispatchPolicy.shouldDispatch(true, false))
        assertFalse(LatencyProbeDispatchPolicy.shouldDispatch(true, true))
    }

    @Test fun skipsProbesAlreadyCompletedByResultOrTimeout() {
        assertFalse(LatencyProbeDispatchPolicy.shouldDispatch(false, true))
    }
}
