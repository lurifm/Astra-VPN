package com.astra.vpn

/** Selects only profiles with a measured successful URLTest response. */
internal object ServiceServerSelectionPolicy {
    data class Candidate(val profile: String, val latencyMs: Long)

    fun bestProfile(
        profileByTag: Map<String, String>,
        latencyByTag: Map<String, Long?>
    ): Candidate? = profileByTag.entries.mapNotNull { (tag, profile) ->
        val latency = latencyByTag[tag] ?: return@mapNotNull null
        if (profile.isBlank() || latency <= 0L) return@mapNotNull null
        Candidate(profile, latency)
    }.minWithOrNull(compareBy<Candidate> { it.latencyMs }.thenBy { it.profile })
}
