package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AdvancedFeaturesTest {
    @Test
    fun protectionStreakCountsConsecutiveCalendarDays() {
        val zone = TimeZone.getTimeZone("UTC")
        val today = Calendar.getInstance(zone).apply {
            set(2026, Calendar.NOVEMBER, 19, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val result = ProtectionStreak.calculate(
            listOf(today.timeInMillis, yesterday.timeInMillis),
            today.timeInMillis,
            zone
        )
        assertEquals(2, result.currentStreak)
        assertEquals(2, result.totalProtectedDays)
    }

    @Test
    fun timeOfDayOptimizerBlendsMatchingHistoricalSamples() {
        val zone = TimeZone.getTimeZone("UTC")
        val nowCalendar = Calendar.getInstance(zone).apply {
            set(2026, Calendar.NOVEMBER, 19, 9, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = nowCalendar.timeInMillis
        val sample1 = (nowCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7); set(Calendar.HOUR_OF_DAY, 9) }.timeInMillis
        val sample2 = (nowCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -14); set(Calendar.HOUR_OF_DAY, 10) }.timeInMillis
        val adjusted = TimeOfDayServerOptimizer.adjustedLatencies(
            profiles = listOf("server"),
            currentLatencies = mapOf("server" to 100L),
            samples = { listOf(
                LatencyStabilityScorer.Sample(sample1, 200L),
                LatencyStabilityScorer.Sample(sample2, 200L)
            ) },
            now = now,
            timeZone = zone
        )
        assertEquals(130L, adjusted["server"])
    }

    @Test
    fun speedDropRequiresHalfSpeedForTenSecondsAndIgnoresIdle() {
        val policy = SpeedDropFailoverPolicy()
        assertFalse(policy.update(3.0, 1_000L, true))
        assertFalse(policy.update(1.0, 2_000L, true))
        assertFalse(policy.update(0.0, 5_000L, true))
        assertFalse(policy.update(1.0, 6_000L, true))
        assertTrue(policy.update(1.0, 16_000L, true))
    }
}
