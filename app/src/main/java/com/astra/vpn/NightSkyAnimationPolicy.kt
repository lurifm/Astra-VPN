package com.astra.vpn

/** Pure timing rules for the lightweight night-sky reveal and meteor shower. */
internal object NightSkyAnimationPolicy {
    fun isNight(hourOfDay: Int): Boolean = hourOfDay in 21..23 || hourOfDay in 0..5

    /** Smooth-step reveal; clamps elapsed time and avoids abrupt star pop-in. */
    fun revealProgress(elapsedMs: Long, durationMs: Long = 2_400L): Float {
        if (durationMs <= 0L) return 1f
        val t = (elapsedMs.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Returns 0 outside a meteor's short flight window, otherwise 0..1. */
    fun meteorProgress(
        elapsedMs: Long,
        offsetMs: Long,
        cycleMs: Long = 14_000L,
        flightMs: Long = 2_300L
    ): Float {
        if (cycleMs <= 0L || flightMs <= 0L) return 0f
        val position = Math.floorMod(elapsedMs + offsetMs, cycleMs)
        if (position >= flightMs) return 0f
        val t = position.toFloat() / flightMs.toFloat()
        return t * t * (3f - 2f * t)
    }
}
