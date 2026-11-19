package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class VpnUsageStreakTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun day(now: Long, offset: Int): Long =
        Calendar.getInstance(utc).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, offset)
        }.timeInMillis

    @Test fun countsCurrentConsecutiveDays() {
        val now = day(1_800_000_000_000L, 0)
        assertEquals(3, VpnUsageStreak.currentDays(listOf(day(now, 0), day(now, -1), day(now, -2)), now, utc))
    }

    @Test fun allowsStreakToContinueUntilEndOfFollowingDay() {
        val now = day(1_800_000_000_000L, 0)
        assertEquals(2, VpnUsageStreak.currentDays(listOf(day(now, -1), day(now, -2)), now, utc))
    }

    @Test fun breaksOnMissingCalendarDay() {
        val now = day(1_800_000_000_000L, 0)
        assertEquals(1, VpnUsageStreak.currentDays(listOf(day(now, 0), day(now, -2)), now, utc))
    }

    @Test fun emptyOrFutureHistoryHasNoStreak() {
        val now = day(1_800_000_000_000L, 0)
        assertEquals(0, VpnUsageStreak.currentDays(emptyList(), now, utc))
        assertEquals(0, VpnUsageStreak.currentDays(listOf(now + 86_400_000L), now, utc))
    }
}
