package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectRequestPolicyTest {
    @Test
    fun connectingBroadcastDoesNotClearPendingUserDisconnect() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterServiceState("connecting"))
    }

    @Test
    fun connectedBroadcastDoesNotClearPendingUserDisconnect() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterServiceState("connected"))
    }

    @Test
    fun errorBroadcastDoesNotClearPendingUserDisconnect() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterServiceState("error"))
    }

    @Test
    fun disconnectedAcknowledgementClearsPendingUserDisconnect() {
        assertTrue(DisconnectRequestPolicy.shouldClearAfterServiceState("disconnected"))
    }

    @Test
    fun persistedDisconnectedStateAllowsRestoreToClearStopMarker() {
        assertTrue(DisconnectRequestPolicy.shouldClearAfterPersistedState("disconnected"))
    }

    @Test
    fun persistedConnectedStateKeepsStopMarkerEvenWhenVpnRouteIsGone() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterPersistedState("connected"))
    }

    @Test
    fun persistedNonTerminalOrUnknownStateKeepsStopMarker() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterPersistedState("connecting"))
        assertFalse(DisconnectRequestPolicy.shouldClearAfterPersistedState("error"))
        assertFalse(DisconnectRequestPolicy.shouldClearAfterPersistedState(null))
    }

    @Test
    fun unknownOrMissingStateFailsClosed() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterServiceState(null))
        assertFalse(DisconnectRequestPolicy.shouldClearAfterServiceState("unknown"))
    }
}
