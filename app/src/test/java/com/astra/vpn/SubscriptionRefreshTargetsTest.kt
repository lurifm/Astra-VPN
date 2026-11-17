package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRefreshTargetsTest {
    @Test fun emptyRetryQueueRefreshesAllCurrentSources() {
        val sources = listOf("https://one.example/sub", "https://two.example/sub")
        assertEquals(sources, SubscriptionRefreshTargets.select(sources, emptySet()))
    }

    @Test fun retryQueueSelectsOnlySourcesThatPreviouslyFailed() {
        val first = "https://one.example/sub"
        val second = "https://two.example/sub"
        val third = "https://three.example/sub"
        assertEquals(
            listOf(second),
            SubscriptionRefreshTargets.select(listOf(first, second, third), setOf(second))
        )
    }

    @Test fun removedFailedSourcesDoNotBlockCurrentSubscriptions() {
        val current = listOf("https://new.example/sub")
        val removedFailures = setOf("https://removed.example/sub")
        assertEquals(current, SubscriptionRefreshTargets.select(current, removedFailures))
    }

    @Test fun newlyAddedSourcesAreRefreshedAlongsideFailedSources() {
        val first = "https://one.example/sub"
        val failed = "https://two.example/sub"
        val added = "https://three.example/sub"
        assertEquals(listOf(failed, added), SubscriptionRefreshTargets.select(listOf(first, failed, added), setOf(failed), setOf(first, failed)))
    }

    @Test fun knownSuccessfulSourcesAreSkippedDuringRetry() {
        val first = "https://one.example/sub"
        val failed = "https://two.example/sub"
        assertEquals(listOf(failed), SubscriptionRefreshTargets.select(listOf(first, failed), setOf(failed), setOf(first, failed)))
    }
    
    @Test fun fingerprintBasedRetrySelectionUsesStoredHashes() {
        val first = SubscriptionSourceFingerprint.of("https://one.example/sub")
        val failed = SubscriptionSourceFingerprint.of("https://two.example/sub")
        val added = SubscriptionSourceFingerprint.of("https://three.example/sub")
        assertEquals(
            listOf(failed, added),
            SubscriptionRefreshTargets.select(
                listOf(first, failed, added),
                setOf(failed),
                setOf(first, failed)
            ).map(SubscriptionSourceFingerprint::of)
        )
    }

    @Test fun emptySourceListRemainsEmpty() {
        assertEquals(emptyList<String>(), SubscriptionRefreshTargets.select(emptyList(), setOf("https://old.example/sub")))
    }

    @Test fun preservesCurrentSourceOrderWhenSeveralNeedRetry() {
        val first = "https://one.example/sub"
        val second = "https://two.example/sub"
        val third = "https://three.example/sub"
        assertEquals(
            listOf(first, third),
            SubscriptionRefreshTargets.select(listOf(first, second, third), setOf(third, first))
        )
    }
}

class SubscriptionRefreshIntervalTest {
    @Test fun keepsSupportedIntervalsUnchanged() {
        assertEquals(1, SubscriptionRefreshInterval.normalize(1))
        assertEquals(2, SubscriptionRefreshInterval.normalize(2))
        assertEquals(12, SubscriptionRefreshInterval.normalize(12))
        assertEquals(24, SubscriptionRefreshInterval.normalize(24))
    }

    @Test fun clampsIntervalsBelowMinimum() {
        assertEquals(1, SubscriptionRefreshInterval.normalize(0))
        assertEquals(1, SubscriptionRefreshInterval.normalize(-5))
    }

    @Test fun clampsIntervalsAboveMaximum() {
        assertEquals(24, SubscriptionRefreshInterval.normalize(25))
        assertEquals(24, SubscriptionRefreshInterval.normalize(100))
    }
}
