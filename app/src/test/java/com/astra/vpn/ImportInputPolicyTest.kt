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

    @Test fun blankSharedTextFallsBackToFileAndNonBlankTextTakesPriority() {
        assertTrue(!ImportInputPolicy.hasNonBlankText(null))
        assertTrue(!ImportInputPolicy.hasNonBlankText("  \n  "))
        assertTrue(ImportInputPolicy.hasNonBlankText("vless://node"))
    }

    @Test fun recognizesSupportedImportSchemesButNotOrdinaryClipboardText() {
        assertTrue(ImportInputPolicy.looksLikeSupportedInput("vless://node"))
        assertTrue(ImportInputPolicy.looksLikeSupportedInput("prefix\nhysteria2://node"))
        assertTrue(ImportInputPolicy.looksLikeSupportedInput("https://example.com/sub"))
        assertTrue(!ImportInputPolicy.looksLikeSupportedInput("hello world"))
    }

    @Test fun acceptsTheMaximumProfileCountAndRejectsMore() {
        ImportInputPolicy.requireProfileCount(ImportInputPolicy.MAX_PROFILES)
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.requireProfileCount(ImportInputPolicy.MAX_PROFILES + 1)
        }
    }

    @Test fun profileBatchLimitAppliesPerImportRatherThanToExistingStorage() {
        ImportInputPolicy.requireProfileCount(ImportInputPolicy.MAX_PROFILES)
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.requireProfileCount(ImportInputPolicy.MAX_PROFILES + 1)
        }
    }

    @Test fun everyFileInAnAtomicBatchMustContainAtLeastOneSupportedProfile() {
        ImportInputPolicy.requireFileProfiles(1, listOf("vless://node"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.requireFileProfiles(2, emptyList())
        }
        assertTrue(error.message!!.contains("файле 2"))
    }

    @Test fun cheapCharSequenceGuardRejectsOversizedInputWithoutUtf8Conversion() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.requireTextLength("x".repeat(ImportInputPolicy.MAX_TOTAL_BYTES + 1))
        }
        ImportInputPolicy.requireTextLength("vless://example")
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

    @Test fun utf8ByteLimitRejectsMultibyteTextBeyondTheSharedImportBudget() {
        val exactLimit = "я".repeat(ImportInputPolicy.MAX_TOTAL_BYTES / 2)
        ImportInputPolicy.validateTextSize(exactLimit)
        assertThrows(IllegalArgumentException::class.java) {
            ImportInputPolicy.validateTextSize(exactLimit + "я")
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
