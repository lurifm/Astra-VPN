package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectButtonHealthPolicyTest {
    @Test fun disconnectedButtonIsAlwaysSilver() {
        assertEquals(ConnectButtonHealthPolicy.Color.SILVER,
            ConnectButtonHealthPolicy.resolve(false, 42L, false))
        assertEquals(ConnectButtonHealthPolicy.Color.SILVER,
            ConnectButtonHealthPolicy.resolve(false, null, null))
    }

    @Test fun greenIncludesZeroThroughTwoHundredWhenMeasurementIsValid() {
        assertEquals(ConnectButtonHealthPolicy.Color.GREEN,
            ConnectButtonHealthPolicy.resolve(true, 1L, true))
        assertEquals(ConnectButtonHealthPolicy.Color.GREEN,
            ConnectButtonHealthPolicy.resolve(true, 150L, true))
        assertEquals(ConnectButtonHealthPolicy.Color.GREEN,
            ConnectButtonHealthPolicy.resolve(true, 200L, true))
    }

    @Test fun yellowStartsAtTwoHundredOneAndIncludesHigherValues() {
        assertEquals(ConnectButtonHealthPolicy.Color.AMBER,
            ConnectButtonHealthPolicy.resolve(true, 201L, true))
        assertEquals(ConnectButtonHealthPolicy.Color.AMBER,
            ConnectButtonHealthPolicy.resolve(true, 249L, true))
        assertEquals(ConnectButtonHealthPolicy.Color.AMBER,
            ConnectButtonHealthPolicy.resolve(true, 250L, true))
        assertEquals(ConnectButtonHealthPolicy.Color.AMBER,
            ConnectButtonHealthPolicy.resolve(true, 900L, true))
    }

    @Test fun unknownLatencyIsCautiousNotGreenOrRed() {
        assertEquals(ConnectButtonHealthPolicy.Color.AMBER,
            ConnectButtonHealthPolicy.resolve(true, null, null))
        assertEquals(ConnectButtonHealthPolicy.Color.AMBER,
            ConnectButtonHealthPolicy.resolve(true, 0L, true))
    }

    @Test fun confirmedTunnelFailureIsRed() {
        assertEquals(ConnectButtonHealthPolicy.Color.RED,
            ConnectButtonHealthPolicy.resolve(true, 35L, false))
        assertEquals(ConnectButtonHealthPolicy.Color.RED,
            ConnectButtonHealthPolicy.resolve(true, null, false))
    }
}
