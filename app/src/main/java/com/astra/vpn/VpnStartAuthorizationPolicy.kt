package com.astra.vpn

/**
 * Authorization gate for explicit service starts initiated by the app,
 * widgets or Quick Settings. It never launches the consent UI itself.
 */
enum class VpnStartAuthorization {
    ALLOWED,
    REQUIRE_USER_CONSENT
}

object VpnStartAuthorizationPolicy {
    fun decide(vpnConsentGranted: Boolean): VpnStartAuthorization =
        if (vpnConsentGranted) {
            VpnStartAuthorization.ALLOWED
        } else {
            VpnStartAuthorization.REQUIRE_USER_CONSENT
        }
}
