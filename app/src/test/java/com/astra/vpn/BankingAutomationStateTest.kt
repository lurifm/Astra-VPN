package com.astra.vpn

import org.junit.Assert.*
import org.junit.Test

class BankingAutomationStateTest {
    @Test fun pauseRequiresConnectedVpnAndSavedProfile() {
        assertTrue(BankingAutomationState().beginAutomaticPause(true, true).autoPausedByBanking)
        assertEquals(BankingAutomationState(), BankingAutomationState().beginAutomaticPause(false, true))
        assertEquals(BankingAutomationState(), BankingAutomationState().beginAutomaticPause(true, false))
    }
    @Test fun manualDisconnectInvalidatesResumeAndSnapshot() {
        val state = BankingAutomationState().beginAutomaticPause(true, true).onManualDisconnect()
        assertFalse(state.autoPausedByBanking); assertFalse(state.wasConnectedBeforePause)
        assertFalse(state.savedProfileAvailable); assertTrue(state.userDisconnectRequested)
    }
    @Test fun manualConnectSupersedesBankingPause() {
        val state = BankingAutomationState().beginAutomaticPause(true, true).onManualConnect()
        assertFalse(state.autoPausedByBanking); assertFalse(state.savedProfileAvailable)
        assertFalse(state.userDisconnectRequested)
    }
    @Test fun previouslyDisconnectedVpnNeverPauses() {
        assertEquals(BankingAutomationState(), BankingAutomationState().beginAutomaticPause(true, false))
    }
    @Test fun duplicatePauseDoesNotReplaceExistingTransition() {
        val state = BankingAutomationState().beginAutomaticPause(true, true)
        assertEquals(state, state.beginAutomaticPause(true, true))
    }
    @Test fun resumeCompletionClearsTemporaryState() {
        val state = BankingAutomationState().beginAutomaticPause(true, true).completeAutomaticResume()
        assertFalse(state.autoPausedByBanking); assertFalse(state.wasConnectedBeforePause)
        assertFalse(state.savedProfileAvailable); assertFalse(state.userDisconnectRequested)
    }
    @Test fun transitionIdAdvances() {
        val initial = BankingAutomationState()
        val paused = initial.beginAutomaticPause(true, true)
        val manual = paused.onManualDisconnect()
        assertTrue(paused.transitionId > initial.transitionId)
        assertTrue(manual.transitionId > paused.transitionId)
    }

    @Test fun cancellingResumeKeepsManualDisconnectGuard() {
        val manual = BankingAutomationState().onManualDisconnect()
        val cancelled = manual.cancelAutomaticResume()
        assertTrue(cancelled.userDisconnectRequested)
        assertFalse(cancelled.autoPausedByBanking)
        assertFalse(cancelled.savedProfileAvailable)
    }

    @Test fun manualOverrideClearsPreviousPauseAndSnapshotFlags() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val manualConnect = paused.onManualConnect()
        assertFalse(manualConnect.autoPausedByBanking)
        assertFalse(manualConnect.wasConnectedBeforePause)
        assertFalse(manualConnect.savedProfileAvailable)
        assertFalse(manualConnect.userDisconnectRequested)
    }
}
