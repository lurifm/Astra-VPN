package com.astra.vpn

/** Limits eagerly-created server rows for large subscriptions. */
internal object ServerListRenderPolicy {
    const val INITIAL_PAGE_SIZE = 100
    const val PAGE_SIZE = 100

    fun visibleProfiles(profiles: List<String>, limit: Int): List<String> =
        profiles.take(limit.coerceAtLeast(0))

    fun nextVisibleLimit(currentLimit: Int, totalProfiles: Int): Int =
        (currentLimit.coerceAtLeast(0) + PAGE_SIZE).coerceAtMost(totalProfiles.coerceAtLeast(0))
}
