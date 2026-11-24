package com.astra.vpn

/** Overflow-safe arithmetic for non-negative network byte counters. */
internal object TrafficCounterMath {
    fun add(first: Long, second: Long): Long {
        val a = first.coerceAtLeast(0L)
        val b = second.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}
