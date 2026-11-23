package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMonitorCallbackPolicyTest {
    @Test
    fun acceptsCallbackFromCurrentAttachedMonitor() {
        assertTrue(
            TrafficMonitorCallbackPolicy.shouldPublish(
                ownerGeneration = 4L,
                currentGeneration = 4L,
                monitorAttached = true,
                stopping = false
            )
        )
    }

    @Test
    fun rejectsCallbackFromOlderMonitorGeneration() {
        assertFalse(
            TrafficMonitorCallbackPolicy.shouldPublish(
                ownerGeneration = 3L,
                currentGeneration = 4L,
                monitorAttached = true,
                stopping = false
            )
        )
    }

    @Test
    fun rejectsCallbackAfterMonitorWasDetached() {
        assertFalse(
            TrafficMonitorCallbackPolicy.shouldPublish(
                ownerGeneration = 4L,
                currentGeneration = 4L,
                monitorAttached = false,
                stopping = false
            )
        )
    }

    @Test
    fun rejectsCallbackWhileServiceIsStopping() {
        assertFalse(
            TrafficMonitorCallbackPolicy.shouldPublish(
                ownerGeneration = 4L,
                currentGeneration = 4L,
                monitorAttached = true,
                stopping = true
            )
        )
    }
}
