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

class SubscriptionRefreshDuePolicyTest {
    private val now = 10L * 24L * 60L * 60L * 1000L
    private val source = "source-a"

    @Test fun skipsKnownSourceUntilItsEffectiveIntervalHasElapsed() {
        val candidate = SubscriptionRefreshCandidate(source, 12, now - 11L * 60L * 60L * 1000L)
        assertEquals(
            emptyList<String>(),
            SubscriptionRefreshDuePolicy.select(listOf(candidate), emptySet(), setOf(source), 2, now)
        )
    }

    @Test fun refreshesAtTheExactProviderIntervalBoundary() {
        val candidate = SubscriptionRefreshCandidate(source, 12, now - 12L * 60L * 60L * 1000L)
        assertEquals(
            listOf(source),
            SubscriptionRefreshDuePolicy.select(listOf(candidate), emptySet(), setOf(source), 2, now)
        )
    }

    @Test fun usesTheLongerOfUserAndProviderIntervals() {
        val candidate = SubscriptionRefreshCandidate(source, 1, now - 3L * 60L * 60L * 1000L)
        assertEquals(
            emptyList<String>(),
            SubscriptionRefreshDuePolicy.select(listOf(candidate), emptySet(), setOf(source), 6, now)
        )
    }

    @Test fun retriesFailedSourcesRegardlessOfTheirRefreshWindow() {
        val candidate = SubscriptionRefreshCandidate(source, 24, now - 5L * 60L * 1000L)
        assertEquals(
            listOf(source),
            SubscriptionRefreshDuePolicy.select(listOf(candidate), setOf(source), setOf(source), 2, now)
        )
    }

    @Test fun refreshesNewSourcesAndSourcesWithoutSuccessfulHistory() {
        val newSource = SubscriptionRefreshCandidate("new", 24, null)
        val oldSource = SubscriptionRefreshCandidate("old", 24, now - 1L)
        assertEquals(
            listOf("new", "old"),
            SubscriptionRefreshDuePolicy.select(
                listOf(newSource, oldSource),
                emptySet(),
                emptySet(),
                2,
                now
            )
        )
    }

    @Test fun clockMovingBackwardsDoesNotSuppressRefreshForever() {
        val candidate = SubscriptionRefreshCandidate(source, 24, now + 1L)
        assertEquals(
            listOf(source),
            SubscriptionRefreshDuePolicy.select(listOf(candidate), emptySet(), setOf(source), 2, now)
        )
    }

    @Test fun providerIntervalIsBoundedAndCannotOverrideUserMinimum() {
        val candidate = SubscriptionRefreshCandidate(source, 720, now - 23L * 60L * 60L * 1000L)
        assertEquals(
            emptyList<String>(),
            SubscriptionRefreshDuePolicy.select(listOf(candidate), emptySet(), setOf(source), 2, now)
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

    @Test fun foregroundIntervalUsesTheSameOneToTwentyFourHourBounds() {
        assertEquals(3_600_000L, SubscriptionRefreshInterval.intervalMillis(0))
        assertEquals(7_200_000L, SubscriptionRefreshInterval.intervalMillis(2))
        assertEquals(86_400_000L, SubscriptionRefreshInterval.intervalMillis(100))
    }
}

class SubscriptionRefreshRetryStateTest {
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
}
