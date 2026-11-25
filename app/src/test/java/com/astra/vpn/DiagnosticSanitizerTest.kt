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
    @Test fun redactsStandaloneSecretFieldsInPlainTextAndJson() {
        val input = """connect failed token=abc123 password: hunter2 {"api_key":"key-789","private_key":"pem-data"}"""
        val output = DiagnosticSanitizer.sanitize(input)
        assertFalse(output.contains("abc123"))
        assertFalse(output.contains("hunter2"))
        assertFalse(output.contains("key-789"))
        assertFalse(output.contains("pem-data"))
        assertTrue(output.contains("token=<redacted>"))
        assertTrue(output.contains("password: <redacted>"))
    }

    @Test fun redactsBearerAuthorizationHeaders() {
        val output = DiagnosticSanitizer.sanitize("request failed Authorization: Bearer eyJhbGciOiJIUzI1NiJ9")
        assertFalse(output.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(output.contains("Authorization: <redacted>"))
    }

    @Test fun preservesNonSecretKeyValueDiagnostics() {
        val input = "connect timeout=8000ms retry=2 status=503"
        assertTrue(DiagnosticSanitizer.sanitize(input).contains(input))
    }

}
