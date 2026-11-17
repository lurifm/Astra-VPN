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

    fun refresh(store: ProfileStore, sourceUrl: String): Int = synchronized(lock) {
        val parsed = SubscriptionParser.importDetailed(sourceUrl)
        store.replaceSubscription(sourceUrl, parsed.links, parsed.metadata)
        parsed.links.size
    }
}
