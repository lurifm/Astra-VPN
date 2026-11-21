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
    fun isCommitted(transaction: FailoverSelectionTransaction, connectedProfile: String?): Boolean =
        connectedProfile == transaction.targetProfile

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
