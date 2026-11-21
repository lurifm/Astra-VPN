package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class BankingVpnCommandPolicyTest {
    private val paused = BankingAutomationState().beginAutomaticPause(true, true)
    private fun input(
        pause: Boolean = true,
        resume: Boolean = false,
        connected: Boolean = true,
        activeLink: String? = "vless://active",
        activeExists: Boolean = true,
        snapshotLink: String? = "vless://active",
        snapshotExists: Boolean = true,
        state: BankingAutomationState = BankingAutomationState(),
        fresh: Boolean = true,
        foreground: String? = "com.example.bank",
        banks: Set<String> = setOf("com.example.bank"),
        usage: Boolean = true
    ) = BankingVpnCommandPolicy.Input(
        pause, resume, connected, activeLink, activeExists, snapshotLink,
        snapshotExists, state, fresh, foreground, banks, usage
    )

    @Test fun pauseRequiresLiveBankObservationAndExactActiveProfile() {
        assertEquals(BankingVpnCommandPolicy.Command.PAUSE_ACTIVE_TUNNEL,
            BankingVpnCommandPolicy.decide(input()))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(activeLink = null)))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(activeExists = false)))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(connected = false)))
    }

    @Test fun pauseCannotOverrideManualDisconnectOrExistingPause() {
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(state = BankingAutomationState().onManualDisconnect())))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(state = paused)))
    }

    @Test fun resumeRequiresFreshNonBankObservationAndExistingSnapshot() {
        assertEquals(BankingVpnCommandPolicy.Command.RESUME_SNAPSHOT,
            BankingVpnCommandPolicy.decide(input(
                pause = false, resume = true, connected = false, state = paused,
                foreground = "com.android.launcher"
            )))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(
                pause = false, resume = true, connected = false, state = paused,
                foreground = "com.example.bank"
            )))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(
                pause = false, resume = true, connected = false, state = paused,
                foreground = "com.android.launcher", snapshotExists = false
            )))
    }

    @Test fun resumeCannotUseSelectedOrDifferentProfileWhenSnapshotMissing() {
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(
                pause = false, resume = true, connected = false, state = paused,
                foreground = "com.android.launcher", snapshotLink = null
            )))
    }

    @Test fun staleUnknownAndNoUsageAccessObservationsRejectEveryCommand() {
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(fresh = false)))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(foreground = null)))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(usage = false)))
    }

    @Test fun secondBankStillBlocksResume() {
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(
                pause = false, resume = true, connected = false, state = paused,
                foreground = "com.example.secondbank",
                banks = setOf("com.example.bank", "com.example.secondbank")
            )))
    }

    @Test fun conflictingOrMissingCommandRejects() {
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(pause = false, resume = false)))
        assertEquals(BankingVpnCommandPolicy.Command.REJECT,
            BankingVpnCommandPolicy.decide(input(pause = true, resume = true)))
    }
}
