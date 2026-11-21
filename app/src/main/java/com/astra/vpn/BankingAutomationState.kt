package com.astra.vpn

/** Pure transition model for banking VPN automation; execution is wired separately. */
internal data class BankingAutomationState(
    val autoPausedByBanking: Boolean = false,
    val wasConnectedBeforePause: Boolean = false,
    val userDisconnectRequested: Boolean = false,
    val savedProfileAvailable: Boolean = false,
    val transitionId: Long = 0L
) {
    fun beginAutomaticPause(profileAvailable: Boolean, vpnWasConnected: Boolean): BankingAutomationState {
        if (!vpnWasConnected || !profileAvailable || autoPausedByBanking || userDisconnectRequested) return this
        return copy(autoPausedByBanking = true, wasConnectedBeforePause = true,
            savedProfileAvailable = true, transitionId = nextId())
    }
    fun onManualDisconnect() = copy(autoPausedByBanking = false,
        wasConnectedBeforePause = false, userDisconnectRequested = true,
        savedProfileAvailable = false, transitionId = nextId())
    fun onManualConnect() = copy(autoPausedByBanking = false,
        wasConnectedBeforePause = false, userDisconnectRequested = false,
        savedProfileAvailable = false, transitionId = nextId())
    fun completeAutomaticResume() = copy(autoPausedByBanking = false,
        wasConnectedBeforePause = false, userDisconnectRequested = false,
        savedProfileAvailable = false, transitionId = nextId())
    fun cancelAutomaticResume() = copy(autoPausedByBanking = false,
        wasConnectedBeforePause = false, savedProfileAvailable = false, transitionId = nextId())
    private fun nextId() = if (transitionId == Long.MAX_VALUE) 1L else transitionId + 1L
}
