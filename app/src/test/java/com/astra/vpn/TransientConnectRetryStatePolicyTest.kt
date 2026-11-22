package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientConnectRetryStatePolicyTest {
    private val profile = "vless://user:secret@example.com:443?security=tls"
    private val fingerprint = TransientConnectRetryStatePolicy.fingerprint(profile)!!
    private val state = PersistedConnectRetry(
        profileFingerprint = fingerprint,
        attempt = 2,
        createdAtWallClockMs = 10_000L,
        dueAtWallClockMs = 18_000L
    )

    @Test fun fingerprintIsStableAndDoesNotContainProfileCredentials() {
        assertEquals(fingerprint, TransientConnectRetryStatePolicy.fingerprint(profile))
        assertNotEquals(profile, fingerprint)
        assertEquals(64, fingerprint.length)
    }

    @Test fun validPendingRetryCanBeRestoredForSameProfile() {
        assertTrue(
            TransientConnectRetryStatePolicy.isValid(
                state, fingerprint, 12_000L,
                userRequestedDisconnect = false,
                hasSelectedProfile = true
            )
        )
        assertEquals(6_000L, TransientConnectRetryStatePolicy.remainingDelayMs(state, 12_000L))
    }

    @Test fun dueRetryRestoresWithZeroDelay() {
        assertEquals(0L, TransientConnectRetryStatePolicy.remainingDelayMs(state, 20_000L))
    }

    @Test fun profileChangeInvalidatesPersistedRetry() {
        assertFalse(
            TransientConnectRetryStatePolicy.isValid(
                state, TransientConnectRetryStatePolicy.fingerprint("ss://different"),
                12_000L, false, true
            )
        )
    }

    @Test fun explicitDisconnectInvalidatesPersistedRetry() {
        assertFalse(
            TransientConnectRetryStatePolicy.isValid(state, fingerprint, 12_000L, true, true)
        )
    }

    @Test fun missingSelectedProfileInvalidatesPersistedRetry() {
        assertFalse(
            TransientConnectRetryStatePolicy.isValid(state, fingerprint, 12_000L, false, false)
        )
    }

    @Test fun expiredOrClockRollbackStateIsRejected() {
        assertFalse(
            TransientConnectRetryStatePolicy.isValid(
                state, fingerprint, 10_000L + TransientConnectRetryStatePolicy.MAX_STATE_AGE_MS + 1L,
                false, true
            )
        )
        assertFalse(
            TransientConnectRetryStatePolicy.isValid(state, fingerprint, 9_999L, false, true)
        )
    }

    @Test fun invalidAttemptAndExcessiveDueWindowAreRejected() {
        assertFalse(
            TransientConnectRetryStatePolicy.isValid(
                state.copy(attempt = 5), fingerprint, 12_000L, false, true
            )
        )
        assertFalse(
            TransientConnectRetryStatePolicy.isValid(
                state.copy(dueAtWallClockMs = 10_000L + TransientConnectRetryStatePolicy.MAX_DUE_WINDOW_MS + 1L),
                fingerprint, 12_000L, false, true
            )
        )
    }
}
