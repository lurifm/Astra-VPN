package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class BankingAutoPausePolicyTest {
    private fun state(
        foreground: String? = "com.example.bank",
        banks: Set<String> = setOf("com.example.bank"),
        connected: Boolean = true,
        autoPaused: Boolean = false,
        wasConnected: Boolean = true,
        profileAvailable: Boolean = true,
        userDisconnect: Boolean = false,
        usageAccess: Boolean = true,
        observationFresh: Boolean = true
    ) = BankingAutoPausePolicy.State(
        foreground, banks, connected, autoPaused, wasConnected,
        profileAvailable, userDisconnect, usageAccess, observationFresh
    )

    @Test fun pausesOnlyWhenBankIsForegroundAndVpnIsConnected() {
        assertEquals(BankingAutoPausePolicy.Action.PAUSE_VPN, BankingAutoPausePolicy.decide(state()))
        assertEquals(BankingAutoPausePolicy.Action.NONE, BankingAutoPausePolicy.decide(state(connected = false)))
        assertEquals(BankingAutoPausePolicy.Action.NONE, BankingAutoPausePolicy.decide(state(userDisconnect = true)))
    }

    @Test fun unknownForegroundDoesNotAssumeBankWasClosed() {
        assertEquals(BankingAutoPausePolicy.Action.NONE,
            BankingAutoPausePolicy.decide(state(foreground = null, autoPaused = true)))
    }

    @Test fun resumesOnlyIfAutomationPausedPreviouslyActiveVpnWithAvailableProfile() {
        assertEquals(BankingAutoPausePolicy.Action.RESUME_VPN,
            BankingAutoPausePolicy.decide(state(foreground = "com.android.launcher", autoPaused = true)))
        assertEquals(BankingAutoPausePolicy.Action.CANCEL_AUTO_RESUME,
            BankingAutoPausePolicy.decide(state(foreground = "com.android.launcher", autoPaused = true, wasConnected = false)))
        assertEquals(BankingAutoPausePolicy.Action.CANCEL_AUTO_RESUME,
            BankingAutoPausePolicy.decide(state(foreground = "com.android.launcher", autoPaused = true, profileAvailable = false)))
    }

    @Test fun manualDisconnectAlwaysCancelsAutomaticResume() {
        assertEquals(BankingAutoPausePolicy.Action.CANCEL_AUTO_RESUME,
            BankingAutoPausePolicy.decide(state(foreground = "com.android.launcher", autoPaused = true, userDisconnect = true)))
    }

    @Test fun noUsageAccessMeansNoAutomaticAction() {
        assertEquals(BankingAutoPausePolicy.Action.NONE,
            BankingAutoPausePolicy.decide(state(usageAccess = false, autoPaused = true, foreground = "com.android.launcher")))
    }

    @Test fun anotherBankDoesNotTriggerResume() {
        assertEquals(BankingAutoPausePolicy.Action.NONE,
            BankingAutoPausePolicy.decide(state(
                foreground = "com.example.secondbank",
                banks = setOf("com.example.bank", "com.example.secondbank"),
                autoPaused = true
            )))
    }

    @Test fun staleObservationCannotPauseOrResumeVpn() {
        assertEquals(BankingAutoPausePolicy.Action.NONE,
            BankingAutoPausePolicy.decide(state(observationFresh = false)))
        assertEquals(BankingAutoPausePolicy.Action.NONE,
            BankingAutoPausePolicy.decide(state(
                foreground = "com.android.launcher",
                autoPaused = true,
                observationFresh = false
            )))
    }

    @Test fun staleObservationDoesNotCancelPendingResumeAsIfManualActionOccurred() {
        assertEquals(BankingAutoPausePolicy.Action.NONE,
            BankingAutoPausePolicy.decide(state(
                foreground = "com.android.launcher",
                autoPaused = true,
                userDisconnect = false,
                observationFresh = false
            )))
    }
}
