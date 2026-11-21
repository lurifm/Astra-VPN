package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverSelectionPolicyTest {
    @Test
    fun failedReplacementRestoresPreviousKnownGoodProfile() {
        val pending = FailoverSelectionTransaction("server-A", "server-B")

        assertEquals(
            "server-A",
            FailoverSelectionPolicy.rollback(pending, "server-B", "server-B")
        )
    }

    @Test
    fun failureDoesNotOverwriteASelectionChangedByTheUser() {
        val pending = FailoverSelectionTransaction("server-A", "server-B")

        assertEquals(
            "server-C",
            FailoverSelectionPolicy.rollback(pending, "server-C", "server-B")
        )
    }

    @Test
    fun unrelatedFailureDoesNotRollBackPendingSelection() {
        val pending = FailoverSelectionTransaction("server-A", "server-B")

        assertEquals(
            "server-B",
            FailoverSelectionPolicy.rollback(pending, "server-B", "server-C")
        )
    }

    @Test
    fun transactionCommitsOnlyWhenTargetProfileIsConnected() {
        val pending = FailoverSelectionTransaction("server-A", "server-B")

        assertTrue(FailoverSelectionPolicy.isCommitted(pending, "server-B"))
        assertFalse(FailoverSelectionPolicy.isCommitted(pending, "server-A"))
        assertFalse(FailoverSelectionPolicy.isCommitted(pending, null))
    }

    @Test
    fun missingPreviousProfileCanBeRestoredAsNull() {
        val pending = FailoverSelectionTransaction(null, "server-B")

        assertEquals(
            null,
            FailoverSelectionPolicy.rollback(pending, "server-B", "server-B")
        )
    }
}
