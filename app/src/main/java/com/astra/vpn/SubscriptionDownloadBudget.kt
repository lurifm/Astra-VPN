package com.astra.vpn

/**
 * Aggregate network budget shared by all HTTPS subscriptions in one refresh batch.
 * The monotonic-clock deadline does not reset between URLs.
 */
class SubscriptionDownloadBudget(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val clockNanos: () -> Long = System::nanoTime
) {
    private val startedAtNanos = clockNanos()
    private val timeoutNanos: Long
    private var downloadedBytes = 0L

    init {
        require(maxBytes >= 0) { "Лимит загрузки не может быть отрицательным" }
        require(timeoutMillis in 1..(Long.MAX_VALUE / NANOS_PER_MILLI)) {
            "Некорректный срок загрузки"
        }
        timeoutNanos = timeoutMillis * NANOS_PER_MILLI
    }

    @Synchronized
    fun consume(byteCount: Int) {
        require(byteCount >= 0) { "Размер блока не может быть отрицательным" }
        ensureTimeRemaining()
        require(byteCount.toLong() <= maxBytes - downloadedBytes) {
            "Общий объём загружаемых подписок превышает установленный лимит"
        }
        downloadedBytes += byteCount.toLong()
    }

    @Synchronized
    fun remainingBytes(): Long {
        ensureTimeRemaining()
        return maxBytes - downloadedBytes
    }

    @Synchronized
    fun remainingTimeoutMillis(operationLimitMillis: Int): Int {
        require(operationLimitMillis > 0)
        val remainingNanos = timeoutNanos - (clockNanos() - startedAtNanos)
        check(remainingNanos > 0) { "Превышено общее время загрузки подписок" }
        val roundedMillis = (remainingNanos - 1) / NANOS_PER_MILLI + 1
        return minOf(operationLimitMillis.toLong(), roundedMillis)
            .coerceAtLeast(1L).toInt()
    }

    private fun ensureTimeRemaining() {
        check(timeoutNanos - (clockNanos() - startedAtNanos) > 0) {
            "Превышено общее время загрузки подписок"
        }
    }

    companion object {
        /** One shared deadline with a normal per-subscription response allowance. */
        fun forSubscriptionCount(count: Int, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): SubscriptionDownloadBudget {
            require(count >= 0) { "Количество подписок не может быть отрицательным" }
            val maxBytes = if (count.toLong() > Long.MAX_VALUE / DEFAULT_MAX_BYTES) Long.MAX_VALUE
                else count.toLong() * DEFAULT_MAX_BYTES
            return SubscriptionDownloadBudget(maxBytes, timeoutMillis)
        }

        const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
