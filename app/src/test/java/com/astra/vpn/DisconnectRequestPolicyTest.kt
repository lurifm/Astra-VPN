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
        assertFalse(DisconnectRequestPolicy.shouldIgnoreWhileDisconnectPending("disconnected"))
    }

    @Test
    fun nonTerminalAndErrorBroadcastsAreIgnoredWhileStopIsPending() {
        assertTrue(DisconnectRequestPolicy.shouldIgnoreWhileDisconnectPending("connecting"))
        assertTrue(DisconnectRequestPolicy.shouldIgnoreWhileDisconnectPending("connected"))
        assertTrue(DisconnectRequestPolicy.shouldIgnoreWhileDisconnectPending("error"))
    }

    @Test
    fun missingOrUnknownBroadcastDoesNotAcknowledgeStop() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterServiceState(null))
        assertTrue(DisconnectRequestPolicy.shouldIgnoreWhileDisconnectPending("unknown"))
    }

    @Test
    fun persistedDisconnectedSnapshotCannotAcknowledgeAStopRequest() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterPersistedState("disconnected"))
    }

    @Test
    fun persistedConnectedOrDisconnectedSnapshotKeepsStopMarker() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterPersistedState("connected"))
        assertFalse(DisconnectRequestPolicy.shouldClearAfterPersistedState("disconnected"))
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
