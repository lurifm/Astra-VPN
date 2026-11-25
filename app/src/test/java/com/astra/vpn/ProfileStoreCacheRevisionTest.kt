package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreCacheRevisionTest {
    @Test
    fun mutationRevisionIsVisibleAcrossIndependentStoreInstances() {
        val before = ProfileStoreCacheRevision.current()
        val committedRevision = ProfileStoreCacheRevision.advance()
        assertTrue(committedRevision > before)
        assertEquals(committedRevision, ProfileStoreCacheRevision.current())
    }

    @Test
    fun everyMutationAdvancesTheSharedRevision() {
        val before = ProfileStoreCacheRevision.current()
        val first = ProfileStoreCacheRevision.advance()
        val second = ProfileStoreCacheRevision.advance()
        assertTrue(first > before)
        assertTrue(second > first)
    }
}
