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
            )
        )
    }

    @Test fun productionSelectionMatchesStoredFingerprintsToOriginalUrls() {
        val first = "https://one.example/sub?token=first"
        val failed = "https://two.example/sub?token=second"
        val added = "https://three.example/sub?token=third"
        val known = setOf(
            SubscriptionSourceFingerprint.of(first),
            SubscriptionSourceFingerprint.of(failed)
        )
        val pending = setOf(SubscriptionSourceFingerprint.of(failed))
        assertEquals(
            listOf(failed, added),
            SubscriptionRefreshTargets.selectSources(listOf(first, failed, added), pending, known)
        )
    }

    @Test fun productionSelectionFallsBackToAllUrlsWhenNoFingerprintIsPending() {
        val first = "https://one.example/sub?token=first"
        val second = "https://two.example/sub?token=second"
        val known = setOf(
            SubscriptionSourceFingerprint.of(first),
            SubscriptionSourceFingerprint.of(second)
        )
        assertEquals(
            listOf(first, second),
            SubscriptionRefreshTargets.selectSources(listOf(first, second), emptySet(), known)
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

    @Test fun periodicRefreshFallsBackToAllKnownSourcesWhenNothingIsPending() {
        val sources = listOf("one", "two")
        assertEquals(sources, SubscriptionRefreshTargets.select(sources, emptySet(), sources.toSet()))
    }

    @Test fun duplicateSourcesKeepTheirOriginalOrder() {
        val sources = listOf("one", "two", "one", "three")
        assertEquals(listOf("one", "one", "three"), SubscriptionRefreshTargets.select(sources, setOf("one", "three"), sources.toSet()))
    }

    @Test fun storedFingerprintIsNotHashedAgain() {
        val fingerprint = SubscriptionSourceFingerprint.of("https://provider.example/sub?token=secret")
        assertEquals(fingerprint, SubscriptionSourceFingerprint.fromStoredValue(fingerprint))
    }

    @Test fun legacyStoredUrlIsConvertedToFingerprint() {
        val legacyUrl = "https://provider.example/sub?token=secret"
        assertEquals(SubscriptionSourceFingerprint.of(legacyUrl), SubscriptionSourceFingerprint.fromStoredValue(legacyUrl))
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

    @Test fun foregroundIntervalUsesTheSameOneToTwentyFourHourBounds() {
        assertEquals(3_600_000L, SubscriptionRefreshInterval.intervalMillis(0))
        assertEquals(7_200_000L, SubscriptionRefreshInterval.intervalMillis(2))
        assertEquals(86_400_000L, SubscriptionRefreshInterval.intervalMillis(100))
    }
}

class SubscriptionRefreshRetryStateTest {
    @Test fun singleFailureIsAddedWithoutDiscardingOtherFailures() {
        val source = "https://provider.example/sub?token=current"
        val otherFailure = SubscriptionSourceFingerprint.of("https://other.example/sub?token=old")
        val legacyFailure = "https://legacy.example/sub?token=legacy"

        assertEquals(
            setOf(
                otherFailure,
                SubscriptionSourceFingerprint.of(legacyFailure),
                SubscriptionSourceFingerprint.of(source)
            ),
            SubscriptionRefreshRetryState.afterSingleResult(
                setOf(otherFailure, legacyFailure),
                source,
                succeeded = false
            )
        )
    }

    @Test fun singleSuccessRemovesOnlyItsOwnFailure() {
        val source = "https://provider.example/sub?token=current"
        val otherFailure = SubscriptionSourceFingerprint.of("https://other.example/sub?token=old")

        assertEquals(
            setOf(otherFailure),
            SubscriptionRefreshRetryState.afterSingleResult(
                setOf(otherFailure, SubscriptionSourceFingerprint.of(source)),
                source,
                succeeded = true
            )
        )
    }

    @Test fun singleSuccessMigratesLegacyEntriesAndKeepsThem() {
        val legacyFailure = "https://legacy.example/sub?token=legacy"
        assertEquals(
            setOf(SubscriptionSourceFingerprint.of(legacyFailure)),
            SubscriptionRefreshRetryState.afterSingleResult(
                setOf(legacyFailure),
                "https://success.example/sub",
                succeeded = true
            )
        )
    }

    @Test fun stoppedWorkerKeepsEarlierFailuresAndAllUnprocessedTargets() {
        val first = "https://one.example/sub?token=one"
        val second = "https://two.example/sub?token=two"
        val third = "https://three.example/sub?token=three"
        val previousFailure = SubscriptionSourceFingerprint.of("https://previous.example/sub?token=old")

        assertEquals(
            setOf(previousFailure, SubscriptionSourceFingerprint.of(second), SubscriptionSourceFingerprint.of(third)),
            SubscriptionRefreshRetryState.pendingAfterStop(
                listOf(first, second, third),
                nextIndex = 1,
                alreadyFailed = setOf(previousFailure)
            )
        )
    }

    @Test fun stoppedWorkerWithOutOfRangeIndexDoesNotThrowOrLoseFailures() {
        val previousFailure = SubscriptionSourceFingerprint.of("https://previous.example/sub")
        assertEquals(
            setOf(previousFailure),
            SubscriptionRefreshRetryState.pendingAfterStop(emptyList(), 10, setOf(previousFailure))
        )
    }
    @Test fun knownSourceSnapshotDoesNotErasePendingFailureFromNewSubscription() {
        val newFailure = SubscriptionSourceFingerprint.of("https://new.example/sub?token=fresh")
        var pending = setOf(newFailure)
        var known = emptySet<String>()
        SubscriptionRefreshRetryState.updateKnownSourcesAtomically(
            readPending = { pending },
            writePending = { pending = it },
            writeKnown = { known = it },
            currentSources = listOf("https://old.example/sub")
        )
        assertEquals(setOf(newFailure), pending)
        assertEquals(setOf(SubscriptionSourceFingerprint.of("https://old.example/sub")), known)
    }

    @Test fun concurrentQueueUpdatesDoNotOverwriteEachOther() {
        val pending = linkedSetOf<String>()
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(2)
        val first = SubscriptionSourceFingerprint.of("https://one.example/sub")
        val second = SubscriptionSourceFingerprint.of("https://two.example/sub")

        listOf(first, second).forEach { fingerprint ->
            Thread {
                try {
                    start.await()
                    SubscriptionRefreshRetryState.updatePendingAtomically(
                        read = { pending.toSet() },
                        write = { next -> pending.clear(); pending.addAll(next) },
                        transform = { it.add(fingerprint) }
                    )
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertEquals(true, done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf(first, second), pending)
    }

}
