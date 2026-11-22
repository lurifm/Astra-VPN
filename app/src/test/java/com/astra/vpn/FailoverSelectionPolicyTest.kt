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
    fun transactionCommitsOnlyWhenTargetProfilePassesValidation() {
        val pending = FailoverSelectionTransaction("server-A", "server-B")

        assertTrue(FailoverSelectionPolicy.isCommitted(pending, "server-B"))
        assertFalse(FailoverSelectionPolicy.isCommitted(pending, "server-A"))
        assertFalse(FailoverSelectionPolicy.isCommitted(pending, null))
    }

    @Test
    fun chainedFailoverKeepsOriginalKnownGoodRollbackProfile() {
        val first = FailoverSelectionPolicy.nextTransaction(
            existing = null,
            currentProfile = "server-A",
            targetProfile = "server-B"
        )
        val second = FailoverSelectionPolicy.nextTransaction(
            existing = first,
            currentProfile = "server-B",
            targetProfile = "server-C"
        )

        assertEquals("server-A", second.previousProfile)
        assertEquals("server-C", second.targetProfile)
    }

    @Test
    fun firstFailoverUsesCurrentProfileAsRollbackAnchor() {
        val next = FailoverSelectionPolicy.nextTransaction(
            existing = null,
            currentProfile = "server-A",
            targetProfile = "server-B"
        )

        assertEquals("server-A", next.previousProfile)
    }

    @Test
    fun missingPreviousProfileCanBeRestoredAsNull() {
        val pending = FailoverSelectionTransaction(null, "server-B")

        assertEquals(
            null,
            FailoverSelectionPolicy.rollback(pending, "server-B", "server-B")
        )
    }

    @Test
    fun secondHopFailureRollsBackToOriginalKnownGoodProfile() {
        val firstHop = FailoverSelectionPolicy.nextTransaction(
            existing = null,
            currentProfile = "server-A",
            targetProfile = "server-B"
        )
        val secondHop = FailoverSelectionPolicy.nextTransaction(
            existing = firstHop,
            currentProfile = "server-B",
            targetProfile = "server-C"
        )

        assertEquals(
            "server-A",
            FailoverSelectionPolicy.rollback(secondHop, "server-C", "server-C")
        )
    }

    @Test
    fun lateFailureFromFirstHopCannotRollbackNewerSecondHop() {
        val firstHop = FailoverSelectionPolicy.nextTransaction(
            existing = null,
            currentProfile = "server-A",
            targetProfile = "server-B"
        )
        val secondHop = FailoverSelectionPolicy.nextTransaction(
            existing = firstHop,
            currentProfile = "server-B",
            targetProfile = "server-C"
        )

        assertEquals(
            "server-C",
            FailoverSelectionPolicy.rollback(secondHop, "server-C", "server-B")
        )
    }
}
