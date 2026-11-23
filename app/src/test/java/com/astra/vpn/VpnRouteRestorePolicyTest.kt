package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRouteRestorePolicyTest {
    @Test
    fun retriesWhenConnectedIsPersistedBeforeAndroidPublishesVpnRoute() {
        assertTrue(
            VpnRouteRestorePolicy.shouldRetry(
                savedConnected = true,
                androidVpnActive = false,
                stopRequested = false,
                attempt = 0
            )
        )
    }

    @Test
    fun doesNotRetryWhenVpnRouteIsAlreadyVisible() {
        assertFalse(
            VpnRouteRestorePolicy.shouldRetry(
                savedConnected = true,
                androidVpnActive = true,
                stopRequested = false,
                attempt = 0
            )
        )
    }

    @Test
    fun doesNotRetryAfterUserRequestedDisconnect() {
        assertFalse(
            VpnRouteRestorePolicy.shouldRetry(
                savedConnected = true,
                androidVpnActive = false,
                stopRequested = true,
                attempt = 0
            )
        )
    }

    @Test
    fun doesNotRetryForStaleNonConnectedSnapshot() {
        assertFalse(
            VpnRouteRestorePolicy.shouldRetry(
                savedConnected = false,
                androidVpnActive = false,
                stopRequested = false,
                attempt = 0
            )
        )
    }

    @Test
    fun retriesAreStrictlyBounded() {
        assertTrue(
            VpnRouteRestorePolicy.shouldRetry(
                savedConnected = true,
                androidVpnActive = false,
                stopRequested = false,
                attempt = VpnRouteRestorePolicy.MAX_RETRIES - 1
            )
        )
        assertFalse(
            VpnRouteRestorePolicy.shouldRetry(
                savedConnected = true,
                androidVpnActive = false,
                stopRequested = false,
                attempt = VpnRouteRestorePolicy.MAX_RETRIES
            )
        )
        assertEquals(6, VpnRouteRestorePolicy.MAX_RETRIES)
        assertEquals(250L, VpnRouteRestorePolicy.RETRY_DELAY_MS)
    }
}
