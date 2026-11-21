package com.astra.vpn

/** Rejects missing/stale service commands and acknowledgements for another pause episode. */
internal object BankingAutomationCommandTokenPolicy {
    fun matches(requestedTransitionId: Long?, state: BankingAutomationState): Boolean =
        requestedTransitionId != null &&
            requestedTransitionId > 0L &&
            requestedTransitionId == state.transitionId &&
            state.autoPausedByBanking &&
            state.wasConnectedBeforePause &&
            !state.userDisconnectRequested &&
            state.savedProfileAvailable
}
