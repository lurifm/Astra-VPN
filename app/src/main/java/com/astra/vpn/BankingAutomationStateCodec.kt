package com.astra.vpn

/**
 * Versioned, non-secret persistence format for the banking automation state.
 * Profile credentials are deliberately excluded and remain in ProfileStore's
 * Android-Keystore-encrypted BankPauseSnapshot.
 */
internal object BankingAutomationStateCodec {
    private const val VERSION = "1"

    fun encode(state: BankingAutomationState): String {
        require(state.transitionId >= 0L) { "Некорректный идентификатор перехода" }
        return listOf(
            VERSION,
            state.autoPausedByBanking.toString(),
            state.wasConnectedBeforePause.toString(),
            state.userDisconnectRequested.toString(),
            state.savedProfileAvailable.toString(),
            state.transitionId.toString()
        ).joinToString("|")
    }

    /** Invalid, truncated, future-version, or contradictory records fail closed. */
    fun decode(value: String?): BankingAutomationState {
        if (value.isNullOrBlank()) return BankingAutomationState()
        val fields = value.split('|')
        if (fields.size != 6 || fields[0] != VERSION) return BankingAutomationState()
        val autoPaused = fields[1].toBooleanStrictOrNull() ?: return BankingAutomationState()
        val wasConnected = fields[2].toBooleanStrictOrNull() ?: return BankingAutomationState()
        val manualDisconnect = fields[3].toBooleanStrictOrNull() ?: return BankingAutomationState()
        val profileAvailable = fields[4].toBooleanStrictOrNull() ?: return BankingAutomationState()
        val transitionId = fields[5].toLongOrNull()?.takeIf { it >= 0L } ?: return BankingAutomationState()

        // A paused state is resumable only when every prerequisite was persisted.
        if (autoPaused && (!wasConnected || !profileAvailable || manualDisconnect)) {
            return BankingAutomationState()
        }
        if (!autoPaused && (wasConnected || profileAvailable)) return BankingAutomationState()
        return BankingAutomationState(
            autoPausedByBanking = autoPaused,
            wasConnectedBeforePause = wasConnected,
            userDisconnectRequested = manualDisconnect,
            savedProfileAvailable = profileAvailable,
            transitionId = transitionId
        )
    }
}
