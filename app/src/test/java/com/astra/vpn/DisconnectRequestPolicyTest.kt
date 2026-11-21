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
    fun unknownOrMissingStateFailsClosed() {
        assertFalse(DisconnectRequestPolicy.shouldClearAfterServiceState(null))
        assertFalse(DisconnectRequestPolicy.shouldClearAfterServiceState("unknown"))
    }
}
