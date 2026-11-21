package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProbeDispatchPolicyTest {
    @Test fun dispatchesOnlyActiveIncompleteProbesWithTimeRemaining() {
        assertTrue(LatencyProbeDispatchPolicy.shouldDispatch(false, false, 1L))
    }

    @Test fun skipsProbesAfterBatchDeadline() {
        assertFalse(LatencyProbeDispatchPolicy.shouldDispatch(false, false, 0L))
        assertFalse(LatencyProbeDispatchPolicy.shouldDispatch(false, false, -1L))
    }

    @Test fun skipsProbesAlreadyCompletedByResultOrTimeout() {
        assertFalse(LatencyProbeDispatchPolicy.shouldDispatch(false, true, 500L))
    }

    @Test fun skipsAllQueuedWorkAfterCancellation() {
        assertFalse(LatencyProbeDispatchPolicy.shouldDispatch(true, false, 500L))
        assertFalse(LatencyProbeDispatchPolicy.shouldDispatch(true, true, 0L))
    }
}
