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
        onResult(result)
        result.getOrThrow()
    }

    fun refresh(store: ProfileStore, sourceUrl: String): Int = synchronized(lock) {
        val parsed = SubscriptionParser.importDetailed(sourceUrl)
        store.replaceSubscription(sourceUrl, parsed.links, parsed.metadata)
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
        onResult: (Result<Int?>) -> Unit = {}
    ): Int? = executeSerialized(
        onAttempt = onAttempt,
        operation = {
            val parsed = SubscriptionParser.importDetailed(sourceUrl)
            if (!store.replaceSubscriptionIfPresent(sourceUrl, parsed.links, parsed.metadata)) null
            else parsed.links.size
        },
        onResult = onResult
    )
}
