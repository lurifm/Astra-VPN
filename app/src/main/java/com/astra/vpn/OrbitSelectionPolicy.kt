package com.astra.vpn

/** Pure selection rules shared by touch, keyboard and accessibility navigation. */
internal object OrbitSelectionPolicy {
    fun initialIndex(selectedNodes: List<Boolean>): Int =
        selectedNodes.indexOfFirst { it }.coerceAtLeast(0)

    fun nextIndex(currentIndex: Int, nodeCount: Int, delta: Int): Int? {
        if (nodeCount <= 0) return null
        return (currentIndex + delta).mod(nodeCount)
    }

    fun hitTest(
        points: List<Pair<Float, Float>>,
        touchX: Float,
        touchY: Float,
        hitRadius: Float
    ): Int? {
        if (points.isEmpty() || hitRadius < 0f) return null
        val nearest = points.indices.minByOrNull { index ->
            val dx = points[index].first - touchX
            val dy = points[index].second - touchY
            dx * dx + dy * dy
        } ?: return null
        val dx = points[nearest].first - touchX
        val dy = points[nearest].second - touchY
        return nearest.takeIf { dx * dx + dy * dy <= hitRadius * hitRadius }
    }
}
