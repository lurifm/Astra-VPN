package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankingAutomationCommandLedgerCodecTest {
    @Test fun roundTripsPendingPauseAndCompletedResume() {
        val pause = BankingAutomationCommandRecord(7L, BankingAutomationCommandRecord.Command.PAUSE, "com.example.bank", BankingAutomationCommandRecord.Status.PENDING)
        assertEquals(pause, BankingAutomationCommandLedgerCodec.decode(BankingAutomationCommandLedgerCodec.encode(pause)))
        val resume = pause.copy(command = BankingAutomationCommandRecord.Command.RESUME, status = BankingAutomationCommandRecord.Status.COMPLETED)
        assertEquals(resume, BankingAutomationCommandLedgerCodec.decode(BankingAutomationCommandLedgerCodec.encode(resume)))
    }
    @Test fun rejectsMalformedAndUnsupportedRecords() {
        listOf(null, "", "2|1|PAUSE|com.example.bank|PENDING", "1|0|PAUSE|com.example.bank|PENDING", "1|1|UNKNOWN|com.example.bank|PENDING", "1|1|PAUSE||PENDING", "1|1|PAUSE|com.example.bank|UNKNOWN", "1|1|PAUSE|com.example.bank").forEach { assertNull(BankingAutomationCommandLedgerCodec.decode(it)) }
    }
    @Test(expected = IllegalArgumentException::class) fun refusesDelimiterInOriginPackage() {
        BankingAutomationCommandLedgerCodec.encode(BankingAutomationCommandRecord(1L, BankingAutomationCommandRecord.Command.PAUSE, "bad|package", BankingAutomationCommandRecord.Status.PENDING))
    }
}
