package com.astra.vpn

/** Validated representation of provider usage metadata persisted locally. */
data class StoredSubscriptionUsage(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
    val updateIntervalHours: Int? = null
)

/**
 * Keeps the compact SharedPreferences representation backward compatible,
 * while making malformed or out-of-range provider values harmless.
 */
object SubscriptionMetadataCodec {
    private const val MAX_EXPIRY_EPOCH_SECONDS = 253402300799L

    fun encode(metadata: SubscriptionMetadata): String = listOf(
        metadata.uploadBytes?.takeIf { it >= 0L },
        metadata.downloadBytes?.takeIf { it >= 0L },
        metadata.totalBytes?.takeIf { it >= 0L },
        metadata.expiresAtEpochSeconds?.takeIf { it in 1..MAX_EXPIRY_EPOCH_SECONDS },
        metadata.updateIntervalHours?.takeIf { it in 1..720 }?.toLong()
    ).joinToString(",") { it?.toString().orEmpty() }

    fun decode(value: String?): StoredSubscriptionUsage {
        val parts = value?.split(",").orEmpty()
        fun counter(index: Int): Long? =
            parts.getOrNull(index)?.takeIf { it.isNotBlank() }?.toLongOrNull()
                ?.takeIf { it >= 0L }
        val expiry = parts.getOrNull(3)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            ?.takeIf { it in 1..MAX_EXPIRY_EPOCH_SECONDS }
        return StoredSubscriptionUsage(
            uploadBytes = counter(0),
            downloadBytes = counter(1),
            totalBytes = counter(2),
            expiresAtEpochSeconds = expiry,
            updateIntervalHours = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it in 1..720 }
        )
    }
}
