package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyTrafficHistoryTest {
    @Test fun keepsOnlySevenMostRecentDays() {
        val history = DailyTrafficHistory()
        (20261101..20261110).forEach { history.record(it, it.toLong(), (it * 2L)) }
        assertEquals(7, history.entries().size)
        assertEquals(20261110, history.entries().first().dayKey)
        assertEquals(20261104, history.entries().last().dayKey)
    }

    @Test fun replacesTotalsForTheSameDay() {
        val history = DailyTrafficHistory()
        history.record(20261118, 100L, 50L)
        history.record(20261118, 250L, 80L)
        assertEquals(DailyTrafficHistory.Totals(250L, 80L), history.total())
    }

    @Test fun encodesAndRestoresHistory() {
        val history = DailyTrafficHistory()
        history.record(20261117, 1024L, 512L)
        history.record(20261118, 2048L, 256L)
        val restored = DailyTrafficHistory.decode(history.encode())
        assertEquals(history.entries(), restored.entries())
        assertEquals(DailyTrafficHistory.Totals(3072L, 768L), restored.total())
    }

    @Test fun ignoresMalformedNegativeAndImpossibleCalendarDates() {
        val restored = DailyTrafficHistory.decode(
            "20261118,10,20;bad;20261117,-1,3;20261116,4,no;20260230,8,9;20261301,3,4"
        )
        assertEquals(listOf(20261118), restored.entries().map { it.dayKey })
    }
}
