package com.astra.vpn

/** Prevents delayed cancellation broadcasts from stopping a different probe. */
internal object ThroughputSessionPolicy {
    fun shouldStop(activeSessionId: String?, requestedSessionId: String?): Boolean =
        !activeSessionId.isNullOrBlank() &&
            !requestedSessionId.isNullOrBlank() &&
            activeSessionId == requestedSessionId
}
