package com.astra.vpn

import java.security.MessageDigest

/**
 * Small, credential-free snapshot of a pending manual-connect retry.
 *
 * Only a SHA-256 identity of the selected profile is persisted; subscription
 * URLs and server credentials are never written to retry preferences.
 */
internal data class PersistedConnectRetry(
    val profileFingerprint: String,
    val attempt: Int,
    val createdAtWallClockMs: Long,
    val dueAtWallClockMs: Long
)

internal object TransientConnectRetryStatePolicy {
    const val MAX_STATE_AGE_MS = 5L * 60L * 1_000L
    const val MAX_DUE_WINDOW_MS = 60L * 1_000L

    fun fingerprint(profile: String?): String? {
        if (profile.isNullOrBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(profile.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun isValid(
        state: PersistedConnectRetry,
        currentProfileFingerprint: String?,
        nowWallClockMs: Long,
        userRequestedDisconnect: Boolean,
        hasSelectedProfile: Boolean
    ): Boolean {
        if (userRequestedDisconnect || !hasSelectedProfile) return false
        if (state.attempt !in 1..4) return false
        if (state.profileFingerprint.isBlank() ||
            state.profileFingerprint != currentProfileFingerprint
        ) return false
        if (state.createdAtWallClockMs <= 0L || state.dueAtWallClockMs <= 0L ||
            nowWallClockMs <= 0L
        ) return false
        if (nowWallClockMs < state.createdAtWallClockMs) return false
        if (nowWallClockMs - state.createdAtWallClockMs > MAX_STATE_AGE_MS) return false
        if (state.dueAtWallClockMs < state.createdAtWallClockMs) return false
        if (state.dueAtWallClockMs - state.createdAtWallClockMs > MAX_DUE_WINDOW_MS) return false
        return true
    }

    fun remainingDelayMs(state: PersistedConnectRetry, nowWallClockMs: Long): Long =
        (state.dueAtWallClockMs - nowWallClockMs).coerceAtLeast(0L)
}
