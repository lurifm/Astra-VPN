package com.astra.vpn

import java.net.HttpURLConnection

/**
 * Owns the single in-flight HTTP connection used by a throughput probe.
 * Closing the registry is terminal: a connection that finishes opening after
 * cancellation is disconnected immediately instead of escaping cleanup.
 */
internal class ThroughputConnectionRegistry {
    private val lock = Any()
    private var closed = false
    private var active: HttpURLConnection? = null

    fun register(connection: HttpURLConnection): Boolean {
        val accepted = synchronized(lock) {
            if (closed || active != null) false else {
                active = connection
                true
            }
        }
        // A second simultaneous registration is a programming error for this
        // single-flight registry; reject it rather than orphaning the first
        // connection when close() later detaches the active one.
        if (!accepted) runCatching { connection.disconnect() }
        return accepted
    }

    fun clear(connection: HttpURLConnection) {
        synchronized(lock) {
            if (active === connection) active = null
        }
    }

    fun close(): HttpURLConnection? = synchronized(lock) {
        closed = true
        active.also { active = null }
    }
}
