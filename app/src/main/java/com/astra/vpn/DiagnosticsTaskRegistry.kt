package com.astra.vpn

import java.util.concurrent.Future

/**
 * Owns the single active diagnostics task and cancels replaced or dismissed work.
 * All state transitions are synchronized because Activity callbacks and executor
 * completion may race with dialog dismissal and Activity teardown.
 */
internal class DiagnosticsTaskRegistry {
    private data class Entry(val token: Long, val future: Future<*>)
    private var active: Entry? = null

    @Synchronized
    fun replace(token: Long, future: Future<*>): Boolean {
        require(token > 0L) { "token must be positive" }
        val previous = active
        active = Entry(token, future)
        previous?.future?.cancel(true)
        return true
    }

    @Synchronized
    fun cancel(token: Long): Boolean {
        val current = active ?: return false
        if (current.token != token) return false
        active = null
        current.future.cancel(true)
        return true
    }

    @Synchronized
    fun cancelAll(): Boolean {
        val current = active ?: return false
        active = null
        current.future.cancel(true)
        return true
    }

    @Synchronized
    fun activeToken(): Long? = active?.token
}
