package com.astra.vpn

import org.junit.Assert.assertEquals
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

    @Test fun acceptsUpdatesOnlyForCurrentlyDispatchedProbes() {
        assertTrue(LatencyProbeDispatchPolicy.shouldAcceptOutboundUpdate(
            isInFlight = true, alreadyCompleted = false, previouslyTimedOut = false
        ))
        assertTrue(LatencyProbeDispatchPolicy.shouldAcceptOutboundUpdate(
            isInFlight = true, alreadyCompleted = true, previouslyTimedOut = true
        ))
        assertFalse(LatencyProbeDispatchPolicy.shouldAcceptOutboundUpdate(
            isInFlight = false, alreadyCompleted = false, previouslyTimedOut = false
        ))
        assertFalse(LatencyProbeDispatchPolicy.shouldAcceptOutboundUpdate(
            isInFlight = false, alreadyCompleted = true, previouslyTimedOut = true
        ))
        assertFalse(LatencyProbeDispatchPolicy.shouldAcceptOutboundUpdate(
            isInFlight = true, alreadyCompleted = true, previouslyTimedOut = false
        ))
        assertFalse(LatencyProbeDispatchPolicy.shouldAcceptOutboundUpdate(
            isInFlight = true,
            alreadyCompleted = true,
            previouslyTimedOut = true,
            discardedByNetworkChange = true
        ))
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

    @Test fun nativeBudgetMatchesPinnedSingBoxTimeoutPhases() {
        assertEquals(10_000L, NativeUrlTestTimingPolicy.DNS_TIMEOUT_MS)
        assertEquals(5_000L, NativeUrlTestTimingPolicy.TCP_CONNECT_TIMEOUT_MS)
        assertEquals(15_000L, NativeUrlTestTimingPolicy.HTTP_CLIENT_TIMEOUT_MS)
        assertEquals(1_000L, NativeUrlTestTimingPolicy.SAFETY_MARGIN_MS)
        assertEquals(31_000L, NativeUrlTestTimingPolicy.NATIVE_MAX_DURATION_MS)
    }

    @Test fun nativeCooldownMatchesPinnedSingBoxTimeoutBudget() {
        assertEquals(10_000L, NativeUrlTestTimingPolicy.SERVER_TIMEOUT_MS)
        assertEquals(21_000L, NativeUrlTestTimingPolicy.cooldownAfterUiTimeoutMs())
    }
}
