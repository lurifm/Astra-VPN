package com.astra.vpn

/** Conservative interpretation of Android network capability flags. */
internal enum class CaptivePortalState {
    NO_NETWORK, CAPTIVE_PORTAL, NO_INTERNET, VALIDATED_INTERNET;

    companion object {
        fun from(hasNetwork: Boolean, captivePortal: Boolean, internet: Boolean, validated: Boolean): CaptivePortalState =
            when {
                !hasNetwork -> NO_NETWORK
                captivePortal -> CAPTIVE_PORTAL
                validated -> VALIDATED_INTERNET
                internet -> NO_INTERNET
                else -> NO_INTERNET
            }
    }
}
