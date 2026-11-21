package com.astra.vpn

/**
 * Pure, fail-closed decision for replaying a durable banking command after monitor restart.
 * This policy deliberately requires the exact origin and transition; it never guesses.
 */
internal object BankingAutomationCommandRecoveryPolicy {
    enum class Decision { CANCEL, WAIT, ACK_PAUSE, ACK_RESUME, REPLAY_PAUSE, REPLAY_RESUME }

    data class Input(
        val command: BankingAutomationCommandRecord,
        val foregroundPackage: String,
        val bankPackages: Set<String>,
        val state: BankingAutomationState,
        val vpnConnected: Boolean,
        val snapshotProfileLink: String?,
        val activeProfileLink: String?,
        val savedProfileExists: Boolean,
        val nowEpochMillis: Long,
        val lastResumeAttemptEpochMillis: Long
    )

    fun decide(input: Input): Decision {
        val record = input.command
        if (record.status != BankingAutomationCommandRecord.Status.PENDING ||
            record.transitionId <= 0L ||
            record.originatingPackage.isBlank() ||
            input.foregroundPackage != record.originatingPackage ||
            record.transitionId != input.state.transitionId ||
            !BankingAutomationCommandTokenPolicy.matches(record.transitionId, input.state)
        ) return Decision.CANCEL

        val snapshot = input.snapshotProfileLink
        if (snapshot.isNullOrBlank() || !input.savedProfileExists) return Decision.CANCEL

        return when (record.command) {
            BankingAutomationCommandRecord.Command.PAUSE -> {
                if (record.originatingPackage !in input.bankPackages ||
                    input.activeProfileLink != snapshot
                ) Decision.CANCEL
                else if (input.vpnConnected) Decision.REPLAY_PAUSE
                else Decision.ACK_PAUSE
            }
            BankingAutomationCommandRecord.Command.RESUME -> {
                if (record.originatingPackage in input.bankPackages) {
                    Decision.CANCEL
                } else if (input.vpnConnected && input.activeProfileLink == snapshot) {
                    Decision.ACK_RESUME
                } else if (input.vpnConnected) {
                    Decision.CANCEL
                } else if (!BankingResumeRetryPolicy.shouldAttempt(
                        input.lastResumeAttemptEpochMillis, input.nowEpochMillis
                    )
                ) Decision.WAIT else Decision.REPLAY_RESUME
            }
        }
    }
}
