package com.astra.vpn

/**
 * Serializes the complete download/parse/commit transaction for subscription
 * sources across the foreground UI and WorkManager in this app process.
 *
 * Serializing only SharedPreferences writes is insufficient: two downloads
 * can finish out of order and let an older response replace newer data.
 */
object SubscriptionRefreshCoordinator {
    private val lock = Any()

    /**
     * Runs the complete refresh transaction, including attempt/result bookkeeping,
     * while holding the same lock used by foreground refreshes and WorkManager.
     * This prevents a slower caller from publishing stale history or retry state
     * after a newer refresh has already committed.
     */
    internal fun <T> executeSerialized(
        onAttempt: () -> Unit = {},
        operation: () -> T,
        onResult: (Result<T>) -> Unit = {}
    ): T = synchronized(lock) {
        onAttempt()
        val result = runCatching(operation)
        val bookkeepingFailure = runCatching { onResult(result) }.exceptionOrNull()
        val operationFailure = result.exceptionOrNull()
        if (operationFailure != null) {
            if (bookkeepingFailure != null && bookkeepingFailure !== operationFailure) {
                operationFailure.addSuppressed(bookkeepingFailure)
            }
            throw operationFailure
        }
        if (bookkeepingFailure != null) throw bookkeepingFailure
        result.getOrThrow()
    }

    fun refresh(
        store: ProfileStore,
        sourceUrl: String,
        downloadBudget: SubscriptionDownloadBudget = SubscriptionDownloadBudget(),
        cancellation: SubscriptionDownloadCancellation? = null
    ): Int = synchronized(lock) {
        cancellation?.throwIfCancelled()
        val parsed = SubscriptionParser.importDetailed(sourceUrl, downloadBudget, cancellation)
        val commit = {
            store.replaceSubscription(sourceUrl, parsed.links, parsed.metadata)
        }
        if (cancellation == null) commit() else cancellation.commitIfActive(commit)
        parsed.links.size
    }

    /**
     * Refresh only if the source still exists at commit time. A null result means
     * it was removed while the response was being downloaded; it is not a failure.
     * Attempt and result callbacks execute before the transaction lock is released.
     */
    fun refreshExisting(
        store: ProfileStore,
        sourceUrl: String,
        onAttempt: () -> Unit = {},
        onResult: (Result<Int?>) -> Unit = {},
        downloadBudget: SubscriptionDownloadBudget = SubscriptionDownloadBudget(),
        cancellation: SubscriptionDownloadCancellation? = null
    ): Int? = executeSerialized(
        onAttempt = onAttempt,
        operation = {
            cancellation?.throwIfCancelled()
            val parsed = SubscriptionParser.importDetailed(sourceUrl, downloadBudget, cancellation)
            val commit = {
                if (!store.replaceSubscriptionIfPresent(sourceUrl, parsed.links, parsed.metadata)) null
                else parsed.links.size
            }
            if (cancellation == null) commit() else cancellation.commitIfActive(commit)
        },
        onResult = onResult
    )
}

/**
 * Persists refresh history and retry state independently so one failed write
 * cannot prevent the other from being attempted. A bookkeeping failure is
 * surfaced to the caller; successful profile data is never silently reported
 * as fully complete when its retry/history state could not be committed.
 */
internal object SubscriptionRefreshBookkeeping {
    fun recordResult(
        result: Result<Int?>,
        recordRetryResult: (succeeded: Boolean) -> Unit,
        recordHistorySuccess: () -> Unit,
        recordHistoryFailure: () -> Unit
    ) {
        if (result.isSuccess) {
            val count = result.getOrNull()
            val historyFailure = if (count != null) {
                runCatching(recordHistorySuccess).exceptionOrNull()
            } else null

            if (historyFailure != null) {
                val retryFailure = runCatching { recordRetryResult(false) }.exceptionOrNull()
                if (retryFailure != null && retryFailure !== historyFailure) {
                    historyFailure.addSuppressed(retryFailure)
                }
                throw historyFailure
            }

            recordRetryResult(true)
            return
        }

        val failures = mutableListOf<Throwable>()
        runCatching(recordHistoryFailure).exceptionOrNull()?.let(failures::add)
        runCatching { recordRetryResult(false) }.exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach { secondary ->
                if (secondary !== primary) primary.addSuppressed(secondary)
            }
            throw primary
        }
    }
}
