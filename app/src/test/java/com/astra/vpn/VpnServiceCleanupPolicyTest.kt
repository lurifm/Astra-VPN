package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceCleanupPolicyTest {
    @Test fun finishesAfterCurrentTunnelWasDetached() {
        assertTrue(VpnServiceCleanupPolicy.shouldFinish(stopping = true, replacementServerAbsent = true))
    }

    @Test fun oldCleanupCannotStopReplacementTunnel() {
        assertFalse(VpnServiceCleanupPolicy.shouldFinish(stopping = false, replacementServerAbsent = false))
    }

    @Test fun doesNotFinishWhileReplacementServerExists() {
        assertFalse(VpnServiceCleanupPolicy.shouldFinish(stopping = true, replacementServerAbsent = false))
    }

    @Test fun doesNotFinishWhenLifecycleHasAlreadyResumed() {
        assertFalse(VpnServiceCleanupPolicy.shouldFinish(stopping = false, replacementServerAbsent = true))
    }
}
