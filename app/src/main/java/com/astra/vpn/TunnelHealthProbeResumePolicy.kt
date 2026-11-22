package com.astra.vpn

/** Decides whether a stale background probe should be replaced after Activity resume. */
internal object TunnelHealthProbeResumePolicy {
    fun shouldReschedule(
        activityActive: Boolean,
        connected: Boolean,
        generationCurrent: Boolean
    ): Boolean = activityActive && connected && !generationCurrent
}
