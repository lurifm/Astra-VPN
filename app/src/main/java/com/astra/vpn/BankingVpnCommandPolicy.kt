package com.astra.vpn

/**
 * Final fail-closed guard immediately before a banking automation command
 * crosses into AstraVpnService. The observation engine proposes an action;
 * this gate revalidates persisted intent and the live tunnel/profile facts.
 */
internal object BankingVpnCommandPolicy {
    enum class Command { REJECT, PAUSE_ACTIVE_TUNNEL, RESUME_SNAPSHOT }

    data class Input(
        val requestedPause: Boolean,
        val requestedResume: Boolean,
        val vpnConnected: Boolean,
        val activeProfileLink: String?,
        val activeProfileExists: Boolean,
        val snapshotProfileLink: String?,
        val snapshotProfileExists: Boolean,
        val state: BankingAutomationState,
        val observationFresh: Boolean,
        val foregroundPackage: String?,
        val bankPackages: Set<String>,
        val usageAccessGranted: Boolean
    )

    fun decide(input: Input): Command {
        if (!input.usageAccessGranted || !input.observationFresh ||
            input.foregroundPackage.isNullOrBlank()
        ) return Command.REJECT

        if (input.requestedPause && !input.requestedResume) {
            if (input.foregroundPackage !in input.bankPackages ||
                !input.vpnConnected ||
                input.state.userDisconnectRequested ||
                !input.activeProfileExists ||
                input.activeProfileLink.isNullOrBlank()
            ) return Command.REJECT
            if (input.state.autoPausedByBanking) {
                // Idempotent retry is permitted only for the exact already-saved
                // profile. A different active profile must never be stopped.
                if (!input.state.wasConnectedBeforePause ||
                    !input.state.savedProfileAvailable ||
                    !input.snapshotProfileExists ||
                    input.snapshotProfileLink != input.activeProfileLink
                ) return Command.REJECT
            }
            return Command.PAUSE_ACTIVE_TUNNEL
        }

        if (input.requestedResume && !input.requestedPause) {
            if (input.foregroundPackage in input.bankPackages ||
                !input.state.autoPausedByBanking ||
                !input.state.wasConnectedBeforePause ||
                input.state.userDisconnectRequested ||
                !input.state.savedProfileAvailable ||
                input.vpnConnected ||
                !input.snapshotProfileExists ||
                input.snapshotProfileLink.isNullOrBlank()
            ) return Command.REJECT
            return Command.RESUME_SNAPSHOT
        }
        return Command.REJECT
    }
}
