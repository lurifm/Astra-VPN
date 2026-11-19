package com.astra.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * Prevents a late diagnostics callback from replacing a newer or dismissed report.
 * Tokens are monotonic for the lifetime of the owning Activity.
 */
internal class DiagnosticsRequestGuard {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun isCurrent(token: Long): Boolean = token > 0L && generation.get() == token

    fun invalidate(token: Long): Boolean =
        token > 0L && generation.compareAndSet(token, token + 1L)
}
