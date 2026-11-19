package com.astra.vpn

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ThroughputHistoryStoreTest {
    @Test
    fun rejectsInvalidSamplesAndStoresValidHistoryWithoutRawProfileKey() {
        val context = Mockito.mock(Context::class.java)
        val prefs = InMemoryPreferences()
        Mockito.``when``(context.getSharedPreferences("astra_throughput_history", Context.MODE_PRIVATE)).thenReturn(prefs)
        val store = ThroughputHistoryStore(context)
        val profile = "vless://secret@example.com:443?uuid=private"

        store.record(profile, Double.NaN, now = 10_000L)
        store.record(profile, -2.0, now = 10_000L)
        store.record(profile, 25.0, now = 10_000L)

        assertEquals(1, store.samples(profile, 10_001L).size)
        assertEquals(25.0, store.medianMbps(profile, 10_001L)!!, 0.0001)
        assertTrue(prefs.values.keys.none { it.contains("secret") || it.contains("example.com") })
    }

    @Test
    fun ignoresDuplicateTooSoonAndStaleSamples() {
        val context = Mockito.mock(Context::class.java)
        val prefs = InMemoryPreferences()
        Mockito.``when``(context.getSharedPreferences("astra_throughput_history", Context.MODE_PRIVATE)).thenReturn(prefs)
        val store = ThroughputHistoryStore(context)
        val profile = "tuic://secret@example.org"

        store.record(profile, 20.0, now = 10_000L)
        store.record(profile, 40.0, now = 20_000L)
        store.record(profile, 30.0, now = 80_000L)

        assertEquals(listOf(20.0, 30.0), store.samples(profile, 80_001L).map { it.downloadMbps })
        assertNull(store.medianMbps(profile, 80_000L + 24L * 60L * 60L * 1000L + 1L))
    }

    private class InMemoryPreferences : SharedPreferences {
        val values = linkedMapOf<String, String>()
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class Editor(private val values: MutableMap<String, String>) : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, String>()
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { if (key != null && value != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) values.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { values.clear() }
        override fun commit(): Boolean = apply { values.putAll(pending) }.let { true }
        override fun apply() { values.putAll(pending) }
    }
}
