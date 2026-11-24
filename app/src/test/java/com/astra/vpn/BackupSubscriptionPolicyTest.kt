package com.astra.vpn

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupSubscriptionPolicyTest {
    @Test fun acceptsDistinctSubscriptionSources() {
        BackupSubscriptionPolicy.requireUniqueSources(
            listOf("https://one.example/sub", "https://two.example/sub")
        )
    }

    @Test fun acceptsEmptySubscriptionList() {
        BackupSubscriptionPolicy.requireUniqueSources(emptyList())
    }

    @Test fun rejectsRepeatedSourceBeforeRestoreCanOverwriteItsOwnGroup() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupSubscriptionPolicy.requireUniqueSources(
                listOf("https://one.example/sub", "https://one.example/sub")
            )
        }
    }

    @Test fun treatsDifferentSubscriptionUrlsAsDifferentSources() {
        BackupSubscriptionPolicy.requireUniqueSources(
            listOf(
                "https://provider.example/sub?token=first",
                "https://provider.example/sub?token=second"
            )
        )
    }
}
