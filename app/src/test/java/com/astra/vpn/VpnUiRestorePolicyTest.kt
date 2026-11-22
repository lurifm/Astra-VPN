package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnUiRestorePolicyTest {
    private val connected = AstraVpnService.STATE_CONNECTED

    @Test
    fun restoresOnlyWhenSavedStateAndroidVpnAndAstraProfileAgree() {
        assertTrue(VpnUiRestorePolicy.isAstraTunnelConnected(connected, connected, true, true))
    }

    @Test
    fun rejectsConnectedFlagWhenAndroidVpnTransportIsGone() {
        assertFalse(VpnUiRestorePolicy.isAstraTunnelConnected(connected, connected, false, true))
    }

    @Test
    fun rejectsAnotherAppsVpnWhenAstraHasNoActiveProfileMarker() {
        assertFalse(VpnUiRestorePolicy.isAstraTunnelConnected(connected, connected, true, false))
    }

    @Test
    fun rejectsStaleConnectedFlagAfterExplicitDisconnect() {
        assertFalse(VpnUiRestorePolicy.isAstraTunnelConnected(
            AstraVpnService.STATE_DISCONNECTED, connected, true, true
        ))
    }

    @Test
    fun rejectsConnectingAndErrorSnapshotsAsConnected() {
        assertFalse(VpnUiRestorePolicy.isAstraTunnelConnected(
            AstraVpnService.STATE_CONNECTING, connected, true, true
        ))
        assertFalse(VpnUiRestorePolicy.isAstraTunnelConnected(
            AstraVpnService.STATE_ERROR, connected, true, true
        ))
    }
}
