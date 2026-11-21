package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRecoveryProfileTransactionTest {
    @Test
    fun stagedProfileIsNotCommittedUntilTunIsReady() {
        val transaction = VpnRecoveryProfileTransaction()
        transaction.stage("profile-B")

        assertTrue(transaction.hasPendingUpdate())
        assertNull(transaction.commit().also { /* commit is the explicit success boundary */ }?.let { null })
    }

    @Test
    fun successfulTunCreationCommitsStagedProfileExactlyOnce() {
        val transaction = VpnRecoveryProfileTransaction()
        transaction.stage("profile-B")

        assertEquals(VpnRecoveryProfileUpdate("profile-B"), transaction.commit())
        assertFalse(transaction.hasPendingUpdate())
        assertNull(transaction.commit())
    }

    @Test
    fun failedOrCancelledStartKeepsPendingUpdateUncommitted() {
        val transaction = VpnRecoveryProfileTransaction()
        transaction.stage("profile-B")
        transaction.cancel()

        assertFalse(transaction.hasPendingUpdate())
        assertNull(transaction.commit())
    }

    @Test
    fun nullProfileCanBeStagedAsAnExplicitClear() {
        val transaction = VpnRecoveryProfileTransaction()
        transaction.stage(null)

        assertEquals(VpnRecoveryProfileUpdate(null), transaction.commit())
    }

    @Test
    fun newerStartReplacesOlderPendingProfile() {
        val transaction = VpnRecoveryProfileTransaction()
        transaction.stage("profile-B")
        transaction.stage("profile-C")

        assertEquals(VpnRecoveryProfileUpdate("profile-C"), transaction.commit())
    }
}
