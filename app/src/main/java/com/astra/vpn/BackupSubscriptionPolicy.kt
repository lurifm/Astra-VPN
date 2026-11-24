package com.astra.vpn

/**
 * Rejects ambiguous subscription groups in an imported backup before any
 * SharedPreferences mutation. A source may own exactly one profile group.
 */
internal object BackupSubscriptionPolicy {
    fun requireUniqueSources(sources: List<String>) {
        require(sources.size == sources.toSet().size) {
            "В резервной копии обнаружена повторяющаяся подписка"
        }
    }
}
