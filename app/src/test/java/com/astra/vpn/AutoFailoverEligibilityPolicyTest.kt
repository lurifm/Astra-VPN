package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFailoverEligibilityPolicyTest {
    private fun eligible(
        connected: Boolean = true,
        connecting: Boolean = false,
        disconnecting: Boolean = false,
        selectingFastest: Boolean = false,
        pending: Boolean = false,
        tunnelInternetOk: Boolean? = false,
        underlay: Boolean = true
    ) = AutoFailoverEligibilityPolicy.shouldSwitch(
        connected = connected,
        connecting = connecting,
        disconnecting = disconnecting,
        selectingFastest = selectingFastest,
        hasPendingFailover = pending,
        tunnelInternetOk = tunnelInternetOk,
        hasValidatedPhysicalInternet = underlay
    )

    @Test
    fun requiresConfirmedTunnelFailureAndValidatedPhysicalInternet() {
        assertTrue(eligible())
        assertFalse(eligible(tunnelInternetOk = null))
        assertFalse(eligible(tunnelInternetOk = true))
        assertFalse(eligible(underlay = false))
    }

    @Test
    fun neverStartsDuringConnectDisconnectOrFastestSelection() {
        assertFalse(eligible(connected = false))
        assertFalse(eligible(connecting = true))
        assertFalse(eligible(disconnecting = true))
        assertFalse(eligible(selectingFastest = true))
    }

    @Test
    fun preventsOverlappingFailoverChains() {
        assertFalse(eligible(pending = true))
    }
}
