package com.astra.vpn

/**
 * Keeps an automatic failover selection provisional until the replacement
 * tunnel reports CONNECTED. Failed attempts restore the last known-good node.
 */
internal data class FailoverSelectionTransaction(
    val previousProfile: String?,
    val targetProfile: String
)

internal object FailoverSelectionPolicy {
    /** A new hop in the same recovery chain must keep its original rollback anchor. */
    fun nextTransaction(
        existing: FailoverSelectionTransaction?,
        currentProfile: String?,
        targetProfile: String
    ): FailoverSelectionTransaction = FailoverSelectionTransaction(
        previousProfile = existing?.previousProfile ?: currentProfile,
        targetProfile = targetProfile
    )

    /** Commit only after the target has passed an end-to-end tunnel health check. */
    fun isCommitted(transaction: FailoverSelectionTransaction, validatedProfile: String?): Boolean =
        validatedProfile == transaction.targetProfile

    fun rollback(
        pending: FailoverSelectionTransaction,
        currentSelection: String?,
        failedProfile: String?
    ): String? {
        // Do not overwrite a newer user selection or roll back an unrelated failure.
        if (failedProfile != pending.targetProfile || currentSelection != pending.targetProfile) {
            return currentSelection
        }
        return pending.previousProfile
    }
}
