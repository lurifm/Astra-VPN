package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppTrafficStatsReaderTest {
    @Test
    fun formatsBytesBelowOneKilobyte() {
        assertEquals("0 Б", AppTrafficStatsReader.formatBytes(0))
        assertEquals("512 Б", AppTrafficStatsReader.formatBytes(512))
    }

    @Test
    fun formatsKilobytesAndMegabytes() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1.0 КБ", AppTrafficStatsReader.formatBytes(1024))
            assertEquals("1.0 МБ", AppTrafficStatsReader.formatBytes(1024L * 1024L))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
