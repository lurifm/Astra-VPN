package com.astra.vpn

/** Prevents completion of an old asynchronous teardown from stopping a replacement tunnel. */
internal object VpnServiceCleanupPolicy {
    fun shouldFinish(stopping: Boolean, replacementServerAbsent: Boolean): Boolean =
        stopping && replacementServerAbsent
}
