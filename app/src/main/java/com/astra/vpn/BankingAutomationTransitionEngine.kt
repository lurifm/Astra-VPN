package com.astra.vpn

/**
 * Pure orchestration boundary between UsageEvents observations and durable
 * banking automation state. Side effects (snapshot encryption and VPN service
 * commands) are deliberately performed by the caller only after this plan.
 */
internal object BankingAutomationTransitionEngine {
    enum class Command { NONE, SAVE_PROFILE_AND_PAUSE, RESUME_SAVED_PROFILE, CLEAR_SNAPSHOT }

    data class Input(
        val foregroundPackage: String?,
        val bankPackages: Set<String>,
        val vpnConnected: Boolean,
        val usageAccessGranted: Boolean,
        val observationAtEpochMillis: Long,
        val nowEpochMillis: Long,
        val savedProfileAvailable: Boolean
    )

    data class Result(
        val command: Command,
        val nextState: BankingAutomationState
    )

    fun evaluate(
        state: BankingAutomationState,
        input: Input
    ): Result {
        val fresh = input.observationAtEpochMillis > 0L &&
            input.nowEpochMillis >= input.observationAtEpochMillis &&
            input.nowEpochMillis - input.observationAtEpochMillis <= MAX_OBSERVATION_AGE_MS

        val action = BankingAutoPausePolicy.decide(
            BankingAutoPausePolicy.State(
                foregroundPackage = input.foregroundPackage,
                bankPackages = input.bankPackages,
                vpnConnected = input.vpnConnected,
                autoPausedByBanking = state.autoPausedByBanking,
                wasConnectedBeforePause = state.wasConnectedBeforePause,
                savedProfileAvailable = state.savedProfileAvailable && input.savedProfileAvailable,
                userDisconnectRequested = state.userDisconnectRequested,
                usageAccessGranted = input.usageAccessGranted,
                observationFresh = fresh
            )
        )

        return when (action) {
            BankingAutoPausePolicy.Action.PAUSE_VPN -> {
                // Never persist a resumable pause until the caller has encrypted
                // and committed the exact active profile snapshot.
                if (!input.savedProfileAvailable) Result(Command.NONE, state)
                else {
                    // If the durable pause intent was committed but the service
                    // rejected/did not receive AUTO_PAUSE, retry while the same
                    // bank remains foreground and the live VPN is still confirmed.
                    // Keep the original transition id and exact snapshot in this case.
                    if (state.autoPausedByBanking && state.wasConnectedBeforePause &&
                        state.savedProfileAvailable && !state.userDisconnectRequested
                    ) {
                        Result(Command.SAVE_PROFILE_AND_PAUSE, state)
                    } else {
                        val next = state.beginAutomaticPause(
                            profileAvailable = true,
                            vpnWasConnected = input.vpnConnected
                        )
                        if (next == state) Result(Command.NONE, state)
                        else Result(Command.SAVE_PROFILE_AND_PAUSE, next)
                    }
                }
            }
            BankingAutoPausePolicy.Action.RESUME_VPN -> {
                if (!input.savedProfileAvailable) {
                    Result(Command.CLEAR_SNAPSHOT, state.cancelAutomaticResume())
                } else Result(Command.RESUME_SAVED_PROFILE, state)
            }
            BankingAutoPausePolicy.Action.CANCEL_AUTO_RESUME ->
                Result(Command.CLEAR_SNAPSHOT, state.cancelAutomaticResume())
            BankingAutoPausePolicy.Action.NONE -> Result(Command.NONE, state)
        }
    }

    const val MAX_OBSERVATION_AGE_MS = 3_000L
}
