package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BankingAutomationStateCodecTest {
    @Test fun roundTripsIdleAndManualDisconnectStates() {
        val idle = BankingAutomationState(transitionId = 9L)
        val manual = idle.onManualDisconnect()
        assertEquals(idle, BankingAutomationStateCodec.decode(BankingAutomationStateCodec.encode(idle)))
        assertEquals(manual, BankingAutomationStateCodec.decode(BankingAutomationStateCodec.encode(manual)))
    }

    @Test fun roundTripsActiveBankPause() {
        val paused = BankingAutomationState().beginAutomaticPause(
            profileAvailable = true,
            vpnWasConnected = true
        )
        assertEquals(paused, BankingAutomationStateCodec.decode(BankingAutomationStateCodec.encode(paused)))
    }

    @Test fun missingOrCorruptRecordsFailClosed() {
        listOf(null, "", "2|true|true|false|true|4", "1|yes|true|false|true|4",
            "1|true|false|false|true|4", "1|true|true|true|true|4",
            "1|false|true|false|false|4", "1|false|false|false|false|-1",
            "1|false|false|false|false|not-a-number", "1|false|false|false|false|0|extra"
        ).forEach { raw ->
            assertEquals("raw=$raw", BankingAutomationState(), BankingAutomationStateCodec.decode(raw))
        }
    }

    @Test fun transitionIdAndFlagsSurviveManualOverride() {
        val paused = BankingAutomationState().beginAutomaticPause(true, true)
        val overridden = paused.onManualDisconnect()
        val restored = BankingAutomationStateCodec.decode(BankingAutomationStateCodec.encode(overridden))
        assertEquals(overridden, restored)
        assertNotEquals(paused, restored)
        assertEquals(true, restored.userDisconnectRequested)
        assertEquals(false, restored.savedProfileAvailable)
    }
}
