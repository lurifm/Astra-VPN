package com.astra.vpn

import java.util.Calendar
import java.util.TimeZone

/** Counts calendar days with at least one successful VPN-connected event. */
internal object ProtectionStreak {
    data class Result(val currentStreak: Int, val totalProtectedDays: Int)

    fun calculate(
        connectedTimestamps: List<Long>,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result {
        if (now <= 0L) return Result(0, 0)
        val protectedDays = connectedTimestamps.asSequence()
            .filter { it > 0L && it <= now }
            .map { dayKey(it, timeZone) }
            .toSet()
        if (protectedDays.isEmpty()) return Result(0, 0)
        val today = dayKey(now, timeZone)
        val yesterday = dayKey(now - 24L * 60L * 60L * 1000L, timeZone)
        var cursor = when {
            today in protectedDays -> today
            yesterday in protectedDays -> yesterday
            else -> return Result(0, protectedDays.size)
        }
        var streak = 0
        val calendar = Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, cursor / 10000)
            set(Calendar.DAY_OF_YEAR, cursor % 10000)
        }
        while (cursor in protectedDays) {
            streak++
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            cursor = calendar.get(Calendar.YEAR) * 10000 + calendar.get(Calendar.DAY_OF_YEAR)
        }
        return Result(streak, protectedDays.size)
    }

    private fun dayKey(timestamp: Long, timeZone: TimeZone): Int {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
        return calendar.get(Calendar.YEAR) * 10000 + calendar.get(Calendar.DAY_OF_YEAR)
    }
}
