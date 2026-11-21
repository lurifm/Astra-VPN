package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class BankingAutomationTransitionEngineTest {
    private fun input(
        foreground: String? = "com.example.bank",
        connected: Boolean = true,
        usage: Boolean = true,
        observedAt: Long = 10_000L,
        now: Long = 10_500L,
        snapshot: Boolean = true
    ) = BankingAutomationTransitionEngine.Input(
        foregroundPackage = foreground,
        bankPackages = setOf("com.example.bank", "com.example.secondbank"),
        vpnConnected = connected,
        usageAccessGranted = usage,
        observationAtEpochMillis = observedAt,
        nowEpochMillis = now,
        savedProfileAvailable = snapshot
    )

    @Test fun bankOpenRequestsSnapshotBeforePauseStateIsCommitted() {
        val result = BankingAutomationTransitionEngine.evaluate(BankingAutomationState(), input())
        assertEquals(BankingAutomationTransitionEngine.Command.SAVE_PROFILE_AND_PAUSE, result.command)
        assertEquals(true, result.nextState.autoPausedByBanking)
        assertEquals(true, result.nextState.wasConnectedBeforePause)
    }

    @Test fun committedPauseIntentRetriesWhenBankStillForegroundAndVpnStillLive() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val result = BankingAutomationTransitionEngine.evaluate(paused, input())
        assertEquals(BankingAutomationTransitionEngine.Command.SAVE_PROFILE_AND_PAUSE, result.command)
        assertEquals(paused, result.nextState)
    }

    @Test fun cannotPauseIfExactProfileSnapshotWasNotSaved() {
        val state = BankingAutomationState()
        val result = BankingAutomationTransitionEngine.evaluate(state, input(snapshot = false))
        assertEquals(BankingAutomationTransitionEngine.Command.NONE, result.command)
        assertEquals(state, result.nextState)
    }

    @Test fun bankCloseRequestsResumeOnlyForPreviouslyAutoPausedVpn() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val result = BankingAutomationTransitionEngine.evaluate(
            paused,
            input(foreground = "com.android.launcher", connected = false)
        )
        assertEquals(BankingAutomationTransitionEngine.Command.RESUME_SAVED_PROFILE, result.command)
        assertEquals(paused, result.nextState)
    }

    @Test fun previouslyDisconnectedVpnIsNeverStarted() {
        val state = BankingAutomationState()
        val result = BankingAutomationTransitionEngine.evaluate(
            state,
            input(foreground = "com.android.launcher", connected = false)
        )
        assertEquals(BankingAutomationTransitionEngine.Command.NONE, result.command)
    }

    @Test fun staleFutureAndUnknownObservationsAreIgnored() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        listOf(
            input(foreground = "com.android.launcher", observedAt = 1L, now = 10_000L),
            input(foreground = "com.android.launcher", observedAt = 11_000L, now = 10_000L),
            input(foreground = null)
        ).forEach { observation ->
            val result = BankingAutomationTransitionEngine.evaluate(paused, observation)
            assertEquals(BankingAutomationTransitionEngine.Command.NONE, result.command)
            assertEquals(paused, result.nextState)
        }
    }

    @Test fun noUsageAccessCannotPauseOrResume() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val result = BankingAutomationTransitionEngine.evaluate(
            paused,
            input(foreground = "com.android.launcher", usage = false)
        )
        assertEquals(BankingAutomationTransitionEngine.Command.NONE, result.command)
    }

    @Test fun secondBankKeepsTunnelPaused() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val result = BankingAutomationTransitionEngine.evaluate(
            paused,
            input(foreground = "com.example.secondbank", connected = false)
        )
        assertEquals(BankingAutomationTransitionEngine.Command.NONE, result.command)
    }

    @Test fun missingSavedProfileCancelsResumeInsteadOfGuessingAnotherProfile() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val result = BankingAutomationTransitionEngine.evaluate(
            paused,
            input(foreground = "com.android.launcher", connected = false, snapshot = false)
        )
        assertEquals(BankingAutomationTransitionEngine.Command.CLEAR_SNAPSHOT, result.command)
        assertEquals(false, result.nextState.autoPausedByBanking)
    }

    @Test fun manualDisconnectGuardPreventsAutoResume() {
        val manual = BankingAutomationState().onManualDisconnect()
        val result = BankingAutomationTransitionEngine.evaluate(
            manual,
            input(foreground = "com.android.launcher", connected = false)
        )
        assertEquals(BankingAutomationTransitionEngine.Command.NONE, result.command)
        assertEquals(manual, result.nextState)
    }
}
