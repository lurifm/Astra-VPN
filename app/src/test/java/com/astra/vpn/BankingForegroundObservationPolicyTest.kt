package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankingForegroundObservationPolicyTest {
    @Test fun firstSampleDoesNotPublish() {
        val policy = BankingForegroundObservationPolicy()
        assertNull(policy.observe("com.bank.app"))
    }

    @Test fun publishesAfterTwoConsecutiveSamples() {
        val policy = BankingForegroundObservationPolicy()
        assertNull(policy.observe("com.bank.app"))
        assertEquals("com.bank.app", policy.observe("com.bank.app"))
    }

    @Test fun unknownGapResetsCandidateAndDoesNotMeanBankClosed() {
        val policy = BankingForegroundObservationPolicy()
        assertNull(policy.observe("com.bank.app"))
        assertNull(policy.observe(null))
        assertNull(policy.observe("com.launcher"))
        assertEquals("com.launcher", policy.observe("com.launcher"))
    }

    @Test fun changedForegroundPackageNeedsItsOwnTwoSamples() {
        val policy = BankingForegroundObservationPolicy()
        policy.observe("com.bank.app")
        assertEquals("com.bank.app", policy.observe("com.bank.app"))
        assertNull(policy.observe("com.launcher"))
        assertEquals("com.launcher", policy.observe("com.launcher"))
    }

    @Test fun repeatedStablePackageIsNotPublishedAgain() {
        val policy = BankingForegroundObservationPolicy()
        policy.observe("com.bank.app")
        assertEquals("com.bank.app", policy.observe("com.bank.app"))
        assertNull(policy.observe("com.bank.app"))
        assertNull(policy.observe("com.bank.app"))
    }

    @Test fun splitScreenAmbiguityCannotTriggerResumeObservation() {
        val policy = BankingForegroundObservationPolicy()
        policy.observe("com.bank.app")
        assertEquals("com.bank.app", policy.observe("com.bank.app"))
        assertNull(policy.observe(null))
        assertNull(policy.observe("com.bank.app"))
    }
}
