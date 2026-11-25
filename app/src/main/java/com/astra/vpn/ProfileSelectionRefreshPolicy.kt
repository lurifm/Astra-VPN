package com.astra.vpn

/**
 * Chooses the post-refresh UI selection without silently jumping to an unrelated
 * server when the previously selected endpoint disappeared or became ambiguous.
 */
internal object ProfileSelectionRefreshPolicy {
    fun resolve(
        previousSelection: String?,
        remappedSelection: String?,
        connectedToPreviousSelection: Boolean
    ): String? = when {
        previousSelection == null -> null
        remappedSelection != null -> remappedSelection
        connectedToPreviousSelection -> previousSelection
        else -> null
    }
    /** Detect profiles whose subscription URI rotated while they remained active. */
    fun matchesRemovedProfile(profile: String?, removedProfiles: Collection<String>): Boolean {
        if (profile.isNullOrBlank() || removedProfiles.isEmpty()) return false
        return removedProfiles.any { removed ->
            profile == removed || ProfileSelectionMatcher.sharesEndpoint(profile, removed)
        }
    }
}
