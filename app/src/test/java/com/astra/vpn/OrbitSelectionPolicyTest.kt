package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrbitSelectionPolicyTest {
    @Test fun initialSelectionUsesSelectedNode() {
        assertEquals(2, OrbitSelectionPolicy.initialIndex(listOf(false, false, true)))
    }

    @Test fun initialSelectionFallsBackToFirstNode() {
        assertEquals(0, OrbitSelectionPolicy.initialIndex(listOf(false, false)))
        assertEquals(0, OrbitSelectionPolicy.initialIndex(emptyList()))
    }

    @Test fun nextSelectionWrapsAtEnd() {
        assertEquals(0, OrbitSelectionPolicy.nextIndex(2, 3, 1))
    }

    @Test fun previousSelectionWrapsAtBeginning() {
        assertEquals(2, OrbitSelectionPolicy.nextIndex(0, 3, -1))
    }

    @Test fun selectionCanMoveAcrossMultiplePositions() {
        assertEquals(1, OrbitSelectionPolicy.nextIndex(0, 5, 6))
        assertEquals(3, OrbitSelectionPolicy.nextIndex(1, 5, -3))
    }

    @Test fun emptyMapHasNoNextSelection() {
        assertNull(OrbitSelectionPolicy.nextIndex(0, 0, 1))
    }

    @Test fun hitTestChoosesNearestPointWithinRadius() {
        assertEquals(1, OrbitSelectionPolicy.hitTest(listOf(0f to 0f, 20f to 20f), 19f, 20f, 5f))
    }

    @Test fun hitTestRejectsTouchesOutsideTargetsAndEmptyMap() {
        assertNull(OrbitSelectionPolicy.hitTest(listOf(0f to 0f), 10f, 10f, 5f))
        assertNull(OrbitSelectionPolicy.hitTest(emptyList(), 0f, 0f, 5f))
    }

    @Test fun hitTestIncludesExactRadiusBoundary() {
        assertEquals(0, OrbitSelectionPolicy.hitTest(listOf(0f to 0f), 3f, 4f, 5f))
    }
}
