package com.astra.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide version for ProfileStore snapshots shared by the UI, services and workers.
 * Per-instance cache clearing cannot invalidate another ProfileStore over the same preferences.
 */
internal object ProfileStoreCacheRevision {
    private val revision = AtomicLong(0L)
    fun current(): Long = revision.get()
    fun advance(): Long = revision.incrementAndGet()
}
