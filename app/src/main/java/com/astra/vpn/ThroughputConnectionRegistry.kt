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
            if (closed) false else {
                active = connection
                true
            }
        }
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
