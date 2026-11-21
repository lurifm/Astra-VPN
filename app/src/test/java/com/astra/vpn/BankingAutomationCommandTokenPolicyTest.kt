package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankingAutomationCommandTokenPolicyTest {
    @Test fun acceptsOnlyCurrentActivePauseTransition() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        assertTrue(BankingAutomationCommandTokenPolicy.matches(paused.transitionId, paused))
    }

    @Test fun rejectsMissingZeroAndStaleTransitionIds() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        assertFalse(BankingAutomationCommandTokenPolicy.matches(null, paused))
        assertFalse(BankingAutomationCommandTokenPolicy.matches(0L, paused))
        assertFalse(BankingAutomationCommandTokenPolicy.matches(paused.transitionId + 1L, paused))
        assertFalse(BankingAutomationCommandTokenPolicy.matches(paused.transitionId - 1L, paused))
    }

    @Test fun rejectsTransitionAfterManualDisconnect() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val manual = paused.onManualDisconnect()
        assertFalse(BankingAutomationCommandTokenPolicy.matches(paused.transitionId, manual))
    }

    @Test fun rejectsCompletedPause() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val completed = paused.completeAutomaticResume()
        assertFalse(BankingAutomationCommandTokenPolicy.matches(paused.transitionId, completed))
    }
}
