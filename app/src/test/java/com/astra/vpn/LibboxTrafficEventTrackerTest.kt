package com.astra.vpn

import org.junit.Assert.*
import org.junit.Test

class LibboxTrafficEventTrackerTest {
    @Test fun updateUsesOutboundRememberedAtConnectionCreation() {
        val tracker = LibboxTrafficEventTracker()
        tracker.onNew("connection-1", "proxy-a")
        tracker.onUpdate("connection-1", null, 100L, 200L)
        tracker.onUpdate("connection-1", null, 30L, 40L)

        assertEquals(LibboxTrafficAccumulator.Totals(130L, 240L), tracker.snapshot()["proxy-a"])
    }

    @Test fun updateCanRefreshOutboundAndClosedConnectionStopsAttribution() {
        val tracker = LibboxTrafficEventTracker()
        tracker.onNew("connection-1", "proxy-a")
        tracker.onUpdate("connection-1", "proxy-b", 10L, 20L)
        tracker.onClosed("connection-1")
        tracker.onUpdate("connection-1", null, 100L, 200L)

        assertEquals(LibboxTrafficAccumulator.Totals(10L, 20L), tracker.snapshot()["proxy-b"])
        assertFalse(tracker.snapshot().containsKey("proxy-a"))
    }

    @Test fun unknownConnectionsAndEmptyOutboundAreIgnored() {
        val tracker = LibboxTrafficEventTracker()
        tracker.onUpdate("unknown", null, 10L, 20L)
        tracker.onNew("empty-tag", " ")
        tracker.onUpdate("empty-tag", null, 10L, 20L)
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test fun resetClearsConnectionsAndTotals() {
        val tracker = LibboxTrafficEventTracker()
        tracker.onNew("connection-1", "proxy")
        tracker.onUpdate("connection-1", null, 10L, 20L)
        tracker.reset()
        tracker.onUpdate("connection-1", null, 50L, 60L)

        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test fun connectionCacheIsBoundedAndEvictsLeastRecentlyUpdatedEntry() {
        val tracker = LibboxTrafficEventTracker()
        tracker.onNew("oldest", "proxy-old")
        for (index in 1..4096) tracker.onNew("connection-$index", "proxy")
        // The oldest entry was evicted when the cache crossed its 4096-entry cap.
        tracker.onUpdate("oldest", null, 100L, 100L)
        tracker.onUpdate("connection-1", null, 7L, 9L)
        assertFalse(tracker.snapshot().containsKey("proxy-old"))
        assertEquals(LibboxTrafficAccumulator.Totals(7L, 9L), tracker.snapshot()["proxy"])
    }

    @Test fun updateRefreshesRecencyBeforeNextEviction() {
        val tracker = LibboxTrafficEventTracker()
        tracker.onNew("first", "proxy-first")
        tracker.onNew("second", "proxy-second")
        tracker.onUpdate("first", null, 1L, 2L)
        for (index in 1..4095) tracker.onNew("connection-$index", "proxy")
        tracker.onUpdate("first", null, 3L, 4L)
        tracker.onUpdate("second", null, 5L, 6L)
        assertEquals(LibboxTrafficAccumulator.Totals(4L, 6L), tracker.snapshot()["proxy-first"])
        assertFalse(tracker.snapshot().containsKey("proxy-second"))
    }
}
