package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthCheckGenerationTest {
    @Test
    fun profileReloadInvalidatesProbeEvenWhenVpnNetworkIsReused() {
        val generation = TunnelHealthCheckGeneration()
        val oldProbe = generation.current()

        generation.advance() // startTunnel() begins a replacement profile

        assertFalse(generation.isCurrent(oldProbe))
        assertTrue(generation.isCurrent(generation.current()))
    }

    @Test
    fun lifecycleBroadcastInvalidatesProbe() {
        val generation = TunnelHealthCheckGeneration()
        val probe = generation.current()

        generation.advance() // CONNECTING / CONNECTED / DISCONNECTED / ERROR

        assertFalse(generation.isCurrent(probe))
    }

    @Test
    fun multipleProbesInSameTunnelRemainCurrentUntilLifecycleChanges() {
        val generation = TunnelHealthCheckGeneration()
        val first = generation.current()
        val second = generation.current()

        assertTrue(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))

        generation.advance()

        assertFalse(generation.isCurrent(first))
        assertFalse(generation.isCurrent(second))
    }

    @Test
    fun generationCanAdvanceRepeatedlyWithoutReturningToPreviousToken() {
        val generation = TunnelHealthCheckGeneration()
        val first = generation.advance()
        val second = generation.advance()
        val third = generation.advance()

        assertTrue(first < second)
        assertTrue(second < third)
        assertTrue(generation.isCurrent(third))
    }
}
