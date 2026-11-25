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

    @Test fun doesNotDispatchNewProbesDuringNetworkHandover() {
        assertFalse(
            LatencyProbeDispatchPolicy.shouldDispatch(
                stopped = false,
                alreadyCompleted = false,
                handoverPending = true
            )
        )
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
        assertFalse(LatencyProbeDispatchPolicy.shouldAcceptOutboundUpdate(
            isInFlight = true,
            alreadyCompleted = false,
            previouslyTimedOut = false,
            handoverPending = true
        ))
    }

    @Test fun rejectsOutboundHistoryOlderThanCurrentDispatch() {
        assertFalse(LatencyProbeDispatchPolicy.isResultFresh(200L, 199L))
        assertTrue(LatencyProbeDispatchPolicy.isResultFresh(200L, 200L))
        assertTrue(LatencyProbeDispatchPolicy.isResultFresh(200L, 201L))
        assertFalse(LatencyProbeDispatchPolicy.isResultFresh(null, 201L))
    }

    @Test fun waitsForTimedOutNativeSlotsBeforeFinishingBatch() {
        assertFalse(LatencyProbeDispatchPolicy.shouldFinish(true, true))
        assertTrue(LatencyProbeDispatchPolicy.shouldFinish(true, false))
        assertFalse(LatencyProbeDispatchPolicy.shouldFinish(false, false))
    }
}

class LatencyEngineRecyclePolicyTest {
    @Test fun serverDeadlineIsExactlyTenSecondsAndRecycleHasSeparateBound() {
        assertEquals(10_000L, LatencyEngineRecyclePolicy.SERVER_TIMEOUT_MS)
        assertEquals(12_000L, LatencyEngineRecyclePolicy.RECYCLE_TIMEOUT_MS)
    }

    @Test fun recycleStartsOnlyWhenServiceIsActiveAndNotAlreadyRecycling() {
        assertTrue(LatencyEngineRecyclePolicy.shouldStartRecycle(false, false))
        assertFalse(LatencyEngineRecyclePolicy.shouldStartRecycle(true, false))
        assertFalse(LatencyEngineRecyclePolicy.shouldStartRecycle(false, true))
    }

    @Test fun dispatchWaitsForRecycleAndHandoverToFinish() {
        assertTrue(LatencyEngineRecyclePolicy.shouldDispatch(false, false, false))
        assertFalse(LatencyEngineRecyclePolicy.shouldDispatch(false, true, false))
        assertFalse(LatencyEngineRecyclePolicy.shouldDispatch(false, false, true))
        assertFalse(LatencyEngineRecyclePolicy.shouldDispatch(true, false, false))
    }

    @Test fun oldEngineResultsAreRejectedDuringAndAfterRecycle() {
        assertFalse(LatencyEngineRecyclePolicy.shouldAcceptResult(true, 4L, 4L))
        assertFalse(LatencyEngineRecyclePolicy.shouldAcceptResult(false, 3L, 4L))
        assertTrue(LatencyEngineRecyclePolicy.shouldAcceptResult(false, 4L, 4L))
    }

    @Test fun handoverRetryPolicyRemainsBoundedWhenUnderlayIsValidated() {
        assertTrue(LatencyEngineRecyclePolicy.shouldRequeueAfterHandover(0, true))
        assertTrue(LatencyEngineRecyclePolicy.shouldRequeueAfterHandover(1, true))
        assertFalse(LatencyEngineRecyclePolicy.shouldRequeueAfterHandover(2, true))
        assertTrue(LatencyEngineRecyclePolicy.shouldRequeueAfterHandover(5, false))
    }
}
