package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class BankingAutomationCommandRecoveryPolicyTest {
    private val banks = setOf("com.example.bank", "com.example.secondbank")
    private val paused = BankingAutomationState().beginAutomaticPause(true, true)
    private fun input(
        command: BankingAutomationCommandRecord.Command = BankingAutomationCommandRecord.Command.PAUSE,
        origin: String = "com.example.bank",
        foreground: String = origin,
        state: BankingAutomationState = paused,
        connected: Boolean = true,
        snapshot: String? = "profile-link",
        active: String? = "profile-link",
        exists: Boolean = true,
        lastAttempt: Long = 0L,
        now: Long = 40_000L
    ) = BankingAutomationCommandRecoveryPolicy.Input(
        BankingAutomationCommandRecord(state.transitionId, command, origin,
            BankingAutomationCommandRecord.Status.PENDING),
        foreground, banks, state, connected, snapshot, active, exists, now, lastAttempt
    )

    @Test fun replaysPauseOnlyForSameForegroundBankAndExactLiveProfile() {
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.REPLAY_PAUSE,
            BankingAutomationCommandRecoveryPolicy.decide(input()))
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.CANCEL,
            BankingAutomationCommandRecoveryPolicy.decide(input(foreground = "com.example.secondbank")))
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.CANCEL,
            BankingAutomationCommandRecoveryPolicy.decide(input(active = "other-profile")))
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.CANCEL,
            BankingAutomationCommandRecoveryPolicy.decide(input(connected = false)))
    }

    @Test fun replaysResumeOnlyForSameNonBankOriginAndAfterCooldown() {
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.REPLAY_RESUME,
            BankingAutomationCommandRecoveryPolicy.decide(input(
                command = BankingAutomationCommandRecord.Command.RESUME,
                origin = "com.android.launcher", connected = false
            )))
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.CANCEL,
            BankingAutomationCommandRecoveryPolicy.decide(input(
                command = BankingAutomationCommandRecord.Command.RESUME,
                origin = "com.android.launcher", foreground = "com.example.bank", connected = false
            )))
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.WAIT,
            BankingAutomationCommandRecoveryPolicy.decide(input(
                command = BankingAutomationCommandRecord.Command.RESUME,
                origin = "com.android.launcher", connected = false, lastAttempt = 20_000L, now = 25_000L
            )))
    }

    @Test fun cancelsStaleTransitionAndMissingSnapshot() {
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.CANCEL,
            BankingAutomationCommandRecoveryPolicy.decide(input(state = paused.onManualDisconnect())))
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.CANCEL,
            BankingAutomationCommandRecoveryPolicy.decide(input(snapshot = null)))
        assertEquals(BankingAutomationCommandRecoveryPolicy.Decision.CANCEL,
            BankingAutomationCommandRecoveryPolicy.decide(input(exists = false)))
    }
}
