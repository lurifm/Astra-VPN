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

    @Test fun acceptsLateNativeMeasurementOnlyForTimedOutProbe() {
        assertTrue(LatencyProbeDispatchPolicy.shouldAcceptMeasurement(false, false))
        assertTrue(LatencyProbeDispatchPolicy.shouldAcceptMeasurement(true, true))
        assertFalse(LatencyProbeDispatchPolicy.shouldAcceptMeasurement(true, false))
    }

    @Test fun waitsForTimedOutNativeSlotsBeforeFinishingBatch() {
        assertFalse(LatencyProbeDispatchPolicy.shouldFinish(true, true))
        assertTrue(LatencyProbeDispatchPolicy.shouldFinish(true, false))
        assertFalse(LatencyProbeDispatchPolicy.shouldFinish(false, false))
    }
}

class NativeUrlTestTimingPolicyTest {
    @Test fun retainsNativeSlotAfterUiTimeoutUntilNativeDeadline() {
        assertTrue(NativeUrlTestTimingPolicy.cooldownAfterUiTimeoutMs() > 0L)
        assertTrue(
            NativeUrlTestTimingPolicy.SERVER_TIMEOUT_MS +
                NativeUrlTestTimingPolicy.cooldownAfterUiTimeoutMs() >=
                NativeUrlTestTimingPolicy.NATIVE_MAX_DURATION_MS
        )
    }

    @Test fun nativeCooldownMatchesPinnedSingBoxTimeoutBudget() {
        assertTrue(NativeUrlTestTimingPolicy.SERVER_TIMEOUT_MS == 10_000L)
        assertTrue(NativeUrlTestTimingPolicy.NATIVE_MAX_DURATION_MS == 31_000L)
        assertTrue(NativeUrlTestTimingPolicy.cooldownAfterUiTimeoutMs() == 21_000L)
    }
}
