package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptivePortalStateTest {
    @Test fun identifiesPortalBeforeGenericInternetFlags() {
        assertEquals(CaptivePortalState.CAPTIVE_PORTAL, CaptivePortalState.from(true, true, true, false))
    }
    @Test fun distinguishesValidatedNetwork() {
        assertEquals(CaptivePortalState.VALIDATED_INTERNET, CaptivePortalState.from(true, false, true, true))
    }
    @Test fun noNetworkIsNotReportedAsPortal() {
        assertEquals(CaptivePortalState.NO_NETWORK, CaptivePortalState.from(false, false, false, false))
    }
}
