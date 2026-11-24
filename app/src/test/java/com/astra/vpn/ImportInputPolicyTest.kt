package com.astra.vpn

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportInputPolicyTest {
    @Test fun acceptsTheMaximumFileCountAndRejectsMore() {
        ImportInputPolicy.requireFileCount(ImportInputPolicy.MAX_FILES)
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.requireFileCount(ImportInputPolicy.MAX_FILES + 1)
        }
    }

    @Test fun acceptsTheMaximumProfileCountAndRejectsMore() {
        ImportInputPolicy.requireProfileCount(ImportInputPolicy.MAX_PROFILES)
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.requireProfileCount(ImportInputPolicy.MAX_PROFILES + 1)
        }
    }

    @Test fun storedProfileLimitIncludesExistingProfiles() {
        ImportInputPolicy.requireStoredProfileCount(900, 100)
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.requireStoredProfileCount(900, 101)
        }
    }

    @Test fun rejectsOversizedCharSequenceBeforeStringConversion() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.validateTextSize("x".repeat(ImportInputPolicy.MAX_TOTAL_BYTES + 1) as CharSequence)
        }
    }

    @Test fun readsBytesUpToTheExactLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(
            bytes,
            ImportInputPolicy.readBytes(ByteArrayInputStream(bytes), bytes.size)
        )
    }

    @Test fun rejectsStreamThatExceedsRemainingBatchBudget() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        assertThrows(ImportInputLimitException::class.java) {
            ImportInputPolicy.readBytes(stream, 4)
        }
    }

    @Test fun validatesUtf8ByteSizeAndAllowsSmallText() {
        ImportInputPolicy.validateTextSize("vless://example")
        assertTrue(ImportInputPolicy.MAX_TOTAL_BYTES > 0)
    }

    @Test fun rejectsTextWhoseCharacterCountAlreadyExceedsByteLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.validateTextSize("a".repeat(ImportInputPolicy.MAX_TOTAL_BYTES + 1))
        }
    }
}
