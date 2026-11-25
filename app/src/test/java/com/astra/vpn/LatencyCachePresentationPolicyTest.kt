package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyCachePresentationPolicyTest {
    @Test
    fun labelsCachedLatencyAsSaved() {
        val label = LatencyCachePresentationPolicy.savedLabel(84L)
        assertEquals("Сохранённый Ping · 84 мс", label)
        assertTrue(LatencyCachePresentationPolicy.isSavedLabel(label))
    }

    @Test
    fun doesNotConfuseLiveOrUnknownStatesWithSavedCache() {
        assertFalse(LatencyCachePresentationPolicy.isSavedLabel("84 мс · хорошая задержка"))
        assertFalse(LatencyCachePresentationPolicy.isSavedLabel("н/д"))
        assertFalse(LatencyCachePresentationPolicy.isSavedLabel(null))
    }
}
