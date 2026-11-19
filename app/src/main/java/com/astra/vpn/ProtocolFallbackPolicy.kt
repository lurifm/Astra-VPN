package com.astra.vpn

/** Chooses a fallback only among imported, independently validated profiles. */
internal object ProtocolFallbackPolicy {
    fun candidates(
        activeProfile: String,
        profiles: List<String>,
        reachable: Set<String>,
        attempted: Set<String> = emptySet()
    ): List<String> {
        val activeProtocol = activeProfile.substringBefore("://").lowercase()
        return profiles.asSequence()
            .filter { it != activeProfile && it in reachable && it !in attempted }
            .distinct()
            .sortedWith(compareBy<String> {
                if (it.substringBefore("://").lowercase() == activeProtocol) 1 else 0
            }.thenBy { it })
            .toList()
    }
}
