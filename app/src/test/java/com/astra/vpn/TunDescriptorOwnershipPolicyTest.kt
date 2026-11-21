package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunDescriptorOwnershipPolicyTest {
    @Test
    fun acceptsDescriptorForCurrentRunningLifecycle() {
        assertTrue(TunDescriptorOwnershipPolicy.shouldAccept(false, true))
    }

    @Test
    fun rejectsDescriptorWhenServiceIsStopping() {
        assertFalse(TunDescriptorOwnershipPolicy.shouldAccept(true, true))
    }

    @Test
    fun rejectsDescriptorFromPreviousLifecycle() {
        assertFalse(TunDescriptorOwnershipPolicy.shouldAccept(false, false))
    }

    @Test
    fun rejectsDescriptorWhenStoppingAndLifecycleIsStale() {
        assertFalse(TunDescriptorOwnershipPolicy.shouldAccept(true, false))
    }
}
