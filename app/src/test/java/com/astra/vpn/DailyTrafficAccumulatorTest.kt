package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyTrafficAccumulatorTest {
    @Test fun accumulatesReceiveAndTransmitDeltas() {
        val usage = DailyTrafficAccumulator(); usage.update(20261118, 1000L, 2000L)
        val result = usage.update(20261118, 1600L, 2300L)
        assertEquals(600L, result.downloadedBytes); assertEquals(300L, result.uploadedBytes)
    }
    @Test fun resetsTotalsOnNewDayAndEstablishesFreshBaseline() {
        val usage = DailyTrafficAccumulator(20261118, 1000L, 1000L, 500L, 250L)
        val first = usage.update(20261119, 9000L, 8000L)
        assertEquals(0L, first.downloadedBytes); assertEquals(0L, first.uploadedBytes)
        val next = usage.update(20261119, 9100L, 8200L)
        assertEquals(100L, next.downloadedBytes); assertEquals(200L, next.uploadedBytes)
    }
    @Test fun handlesCounterResetWithoutAddingNegativeBytes() {
        val usage = DailyTrafficAccumulator(); usage.update(20261118, 1000L, 1000L)
        val reset = usage.update(20261118, 10L, 20L)
        assertEquals(0L, reset.downloadedBytes); assertEquals(0L, reset.uploadedBytes)
        val next = usage.update(20261118, 40L, 70L)
        assertEquals(30L, next.downloadedBytes); assertEquals(50L, next.uploadedBytes)
    }
    @Test fun saturatesDailyTotalsInsteadOfWrappingNegative() {
        val usage = DailyTrafficAccumulator(20261118, 1L, 1L, Long.MAX_VALUE - 5L, Long.MAX_VALUE - 10L)
        val result = usage.update(20261118, 10L, 20L)
        assertEquals(Long.MAX_VALUE, result.downloadedBytes); assertEquals(Long.MAX_VALUE, result.uploadedBytes)
    }
    @Test fun formatsBytesInReadableUnits() {
        assertEquals("500 Б", DailyTrafficAccumulator.formatBytes(500L))
        assertEquals("1.0 КБ", DailyTrafficAccumulator.formatBytes(1024L))
    }
}
