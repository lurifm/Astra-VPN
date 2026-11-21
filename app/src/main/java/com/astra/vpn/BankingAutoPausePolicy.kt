package com.astra.vpn

/**
 * Pure decision policy for banking-app VPN pause/resume.
 * A null foreground observation is unknown, never proof that a bank closed.
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
        val usageAccessGranted: Boolean
    )

    fun decide(state: State): Action {
        if (!state.usageAccessGranted) return Action.NONE
        val foreground = state.foregroundPackage ?: return Action.NONE
        if (foreground in state.bankPackages) {
            return if (state.vpnConnected && !state.autoPausedByBanking &&
                !state.userDisconnectRequested
            ) Action.PAUSE_VPN else Action.NONE
        }
        if (!state.autoPausedByBanking) return Action.NONE
        if (state.userDisconnectRequested || !state.wasConnectedBeforePause ||
            !state.savedProfileAvailable
        ) return Action.CANCEL_AUTO_RESUME
        return Action.RESUME_VPN
    }
}
