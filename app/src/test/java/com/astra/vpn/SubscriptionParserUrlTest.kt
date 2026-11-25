package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SubscriptionParserUrlTest {
    @Test fun acceptsHttpsSubscriptionUrls() {
        val url = SubscriptionParser.validateSubscriptionUrl("https://example.com/sub?token=abc")
        assertEquals("https", url.protocol)
        assertEquals("example.com", url.host)
    }

    @Test fun acceptsHttpsWithValidExplicitPort() {
        val url = SubscriptionParser.validateSubscriptionUrl("https://example.com:8443/sub")
        assertEquals(8443, url.port)
    }

    @Test fun rejectsNonHttpsUrls() {
        assertRejected("http://example.com/sub")
        assertRejected("ftp://example.com/sub")
    }

    @Test fun rejectsCredentialsEmbeddedInUrl() {
        assertRejected("https://user:password@example.com/sub")
    }

    @Test fun rejectsMissingHostAndInvalidPorts() {
        assertRejected("https:///subscription")
        assertRejected("https://example.com:0/sub")
        assertRejected("https://example.com:70000/sub")
    }

    @Test fun acceptsImportTextWithinTwoMegabytes() {
        SubscriptionParser.validateInputSize("x".repeat(2 * 1024 * 1024))
    }

    @Test fun rejectsImportTextLargerThanTwoMegabytes() {
        try {
            SubscriptionParser.validateInputSize("x".repeat(2 * 1024 * 1024 + 1))
            fail("Expected oversized import text to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected size limit.
        }
    }

    @Test fun validatesCyrillicAtTheExactUtf8ByteLimit() {
        SubscriptionParser.validateInputSize("я".repeat(ImportInputPolicy.MAX_TOTAL_BYTES / 2))
        try {
            SubscriptionParser.validateInputSize("я".repeat(ImportInputPolicy.MAX_TOTAL_BYTES / 2 + 1))
            fail("Expected oversized UTF-8 text to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected UTF-8 byte limit.
        }
    }

    @Test fun validatesSupplementaryCharactersAtTheExactUtf8ByteLimit() {
        SubscriptionParser.validateInputSize("😀".repeat(ImportInputPolicy.MAX_TOTAL_BYTES / 4))
        try {
            SubscriptionParser.validateInputSize("😀".repeat(ImportInputPolicy.MAX_TOTAL_BYTES / 4 + 1))
            fail("Expected oversized UTF-8 text to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected UTF-8 byte limit.
        }
    }

    private fun assertRejected(value: String) {
        try {
            SubscriptionParser.validateSubscriptionUrl(value)
            fail("Expected URL to be rejected: $value")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        } catch (_: java.net.MalformedURLException) {
            // A malformed URL is also correctly rejected.
        }
    }
}
