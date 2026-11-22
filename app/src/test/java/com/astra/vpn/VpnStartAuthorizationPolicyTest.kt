package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnStartAuthorizationPolicyTest {
    @Test
    fun allowsStartOnlyWhenAndroidVpnConsentIsGranted() {
        assertEquals(
            VpnStartAuthorization.ALLOWED,
            VpnStartAuthorizationPolicy.decide(vpnConsentGranted = true)
        )
    }

    @Test
    fun requiresUserConsentWhenAndroidVpnPermissionWasRevoked() {
        assertEquals(
            VpnStartAuthorization.REQUIRE_USER_CONSENT,
            VpnStartAuthorizationPolicy.decide(vpnConsentGranted = false)
        )
    }
}
