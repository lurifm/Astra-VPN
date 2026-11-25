package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnActionAdmissionPolicyTest {
    @Test
    fun manualDisconnectBlocksHealthChecksBeforeServiceAcknowledgement() {
        assertFalse(
            VpnActionAdmissionPolicy.shouldRunHealthCheck(
                connected = true, disconnecting = true,
                isFinishing = false, isDestroyed = false
            )
        )
    }

    @Test
    fun healthChecksRequireAnActiveVisibleNonDisconnectingTunnel() {
        assertTrue(
            VpnActionAdmissionPolicy.shouldRunHealthCheck(
                connected = true, disconnecting = false,
                isFinishing = false, isDestroyed = false
            )
        )
        assertFalse(
            VpnActionAdmissionPolicy.shouldRunHealthCheck(
                connected = false, disconnecting = false,
                isFinishing = false, isDestroyed = false
            )
        )
        assertFalse(
            VpnActionAdmissionPolicy.shouldRunHealthCheck(
                connected = true, disconnecting = false,
                isFinishing = true, isDestroyed = false
            )
        )
    }

    @Test
    fun manualDisconnectBlocksQueuedProfileStart() {
        assertFalse(
            VpnActionAdmissionPolicy.shouldStartSelectedProfile(
                connecting = false, disconnecting = true,
                hasPendingConfig = false
            )
        )
    }

    @Test
    fun duplicateOrPendingProfileStartsRemainBlocked() {
        assertFalse(
            VpnActionAdmissionPolicy.shouldStartSelectedProfile(
                connecting = true, disconnecting = false,
                hasPendingConfig = false
            )
        )
        assertFalse(
            VpnActionAdmissionPolicy.shouldStartSelectedProfile(
                connecting = false, disconnecting = false,
                hasPendingConfig = true
            )
        )
        assertTrue(
            VpnActionAdmissionPolicy.shouldStartSelectedProfile(
                connecting = false, disconnecting = false,
                hasPendingConfig = false
            )
        )
    }

    @Test
    fun automaticFailoverCannotOverrideManualDisconnect() {
        assertFalse(
            VpnActionAdmissionPolicy.shouldAutoFailover(
                connected = true, connecting = false,
                disconnecting = true, selectingFastest = false
            )
        )
    }

    @Test
    fun failoverRequiresStableConnectedTunnelAndNoOtherSelection() {
        assertTrue(
            VpnActionAdmissionPolicy.shouldAutoFailover(
                connected = true, connecting = false,
                disconnecting = false, selectingFastest = false
            )
        )
        assertFalse(
            VpnActionAdmissionPolicy.shouldAutoFailover(
                connected = true, connecting = true,
                disconnecting = false, selectingFastest = false
            )
        )
        assertFalse(
            VpnActionAdmissionPolicy.shouldAutoFailover(
                connected = true, connecting = false,
                disconnecting = false, selectingFastest = true
            )
        )
    }
}
