package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedFailoverEligibilityPolicyTest {
    @Test
    fun requiresVerifiedHealthyActiveTunnel() {
        assertTrue(
            AutoSpeedFailoverEligibilityPolicy.shouldEvaluate(
                connected = true,
                connecting = false,
                tunnelInternetOk = true,
                selectingFastest = false,
                hasPendingFailover = false,
                enabled = true
            )
        )
        assertFalse(
            AutoSpeedFailoverEligibilityPolicy.shouldEvaluate(
                connected = true,
                connecting = false,
                tunnelInternetOk = null,
                selectingFastest = false,
                hasPendingFailover = false,
                enabled = true
            )
        )
        assertFalse(
            AutoSpeedFailoverEligibilityPolicy.shouldEvaluate(
                connected = true,
                connecting = false,
                tunnelInternetOk = false,
                selectingFastest = false,
                hasPendingFailover = false,
                enabled = true
            )
        )
    }

    @Test
    fun blocksOverlappingAndDisabledFailovers() {
        fun eligible(
            connecting: Boolean = false,
            selectingFastest: Boolean = false,
            pending: Boolean = false,
            enabled: Boolean = true
        ) = AutoSpeedFailoverEligibilityPolicy.shouldEvaluate(
            connected = true,
            connecting = connecting,
            tunnelInternetOk = true,
            selectingFastest = selectingFastest,
            hasPendingFailover = pending,
            enabled = enabled
        )

        assertFalse(eligible(connecting = true))
        assertFalse(eligible(selectingFastest = true))
        assertFalse(eligible(pending = true))
        assertFalse(eligible(enabled = false))
    }

    @Test
    fun disconnectedTunnelCannotTriggerSpeedFailover() {
        assertFalse(
            AutoSpeedFailoverEligibilityPolicy.shouldEvaluate(
                connected = false,
                connecting = false,
                tunnelInternetOk = true,
                selectingFastest = false,
                hasPendingFailover = false,
                enabled = true
            )
        )
    }
}
