package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test fun redactsSupportedProxyProfileUris() {
        val input = "failed vless://uuid:secret@example.com:443?security=reality and hysteria2://user:pass@example.net:443"
        val output = DiagnosticSanitizer.sanitize(input)
        assertFalse(output.contains("uuid:secret"))
        assertFalse(output.contains("user:pass"))
        assertTrue(output.contains("<profile-uri-redacted>"))
    }

    @Test fun redactsInlineCredentialsWithoutProxyScheme() {
        val output = DiagnosticSanitizer.sanitize("connect https://user:password@example.com/path failed")
        assertFalse(output.contains("user:password"))
        assertTrue(output.contains("<uri-redacted>"))
    }

    @Test fun redactsSubscriptionUrlsContainingQueryTokens() {
        val input = "refresh failed for https://example.com/subscription?token=secret&user=alice"
        val output = DiagnosticSanitizer.sanitize(input)
        assertFalse(output.contains("token=secret"))
        assertFalse(output.contains("user=alice"))
        assertTrue(output.contains("<uri-redacted>"))
    }

    @Test fun redactsUnknownProxySchemes() {
        val output = DiagnosticSanitizer.sanitize("failed wireguard://private-key@vpn.example:51820")
        assertFalse(output.contains("private-key"))
        assertTrue(output.contains("<uri-redacted>"))
    }

    @Test fun preservesOrdinaryDiagnosticText() {
        val input = "dns resolve timeout after 8000ms"
        assertTrue(DiagnosticSanitizer.sanitize(input).contains(input))
    }
}
