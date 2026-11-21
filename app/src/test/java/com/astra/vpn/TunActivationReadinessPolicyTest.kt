package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunActivationReadinessPolicyTest {
    @Test
    fun readyOnlyAfterConfigurationAndTunAreBothAccepted() {
        assertTrue(TunActivationReadinessPolicy.isReady(true, true))
    }

    @Test
    fun rejectsTunCallbackBeforeConfigurationAcceptance() {
        assertFalse(TunActivationReadinessPolicy.isReady(false, true))
    }

    @Test
    fun rejectsAcceptedConfigurationWithoutTunDescriptor() {
        assertFalse(TunActivationReadinessPolicy.isReady(true, false))
    }

    @Test
    fun rejectsWhenNeitherSideIsReady() {
        assertFalse(TunActivationReadinessPolicy.isReady(false, false))
    }
}
