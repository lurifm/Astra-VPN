package com.astra.vpn

/**
 * Pure decision policy for banking-app VPN pause/resume.
 *
 * A foreground package is actionable only when the monitor has delivered a
 * fresh, stable observation. Null, stale, or ambiguous observations are
 * unknown and must never be interpreted as the bank having closed.
 */
internal object BankingAutoPausePolicy {
    enum class Action { NONE, PAUSE_VPN, RESUME_VPN, CANCEL_AUTO_RESUME }

    data class State(
        val foregroundPackage: String?,
        val bankPackages: Set<String>,
        val vpnConnected: Boolean,
        val autoPausedByBanking: Boolean,
        val wasConnectedBeforePause: Boolean,
        val savedProfileAvailable: Boolean,
        val userDisconnectRequested: Boolean,
        val usageAccessGranted: Boolean,
        val observationFresh: Boolean = true
    )

    fun decide(state: State): Action {
        if (!state.usageAccessGranted || !state.observationFresh) return Action.NONE
        val foreground = state.foregroundPackage ?: return Action.NONE
        if (foreground in state.bankPackages) {
            val pauseWasCommittedButTunnelStillLive = state.autoPausedByBanking &&
                state.wasConnectedBeforePause && state.savedProfileAvailable
            return if (state.vpnConnected && !state.userDisconnectRequested &&
                (!state.autoPausedByBanking || pauseWasCommittedButTunnelStillLive)
            ) Action.PAUSE_VPN else Action.NONE
        }
        if (!state.autoPausedByBanking) return Action.NONE
        if (state.userDisconnectRequested || !state.wasConnectedBeforePause ||
            !state.savedProfileAvailable
        ) return Action.CANCEL_AUTO_RESUME
        return Action.RESUME_VPN
    }
}
