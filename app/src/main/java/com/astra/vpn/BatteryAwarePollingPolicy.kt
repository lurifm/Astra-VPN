package com.astra.vpn

/** Keeps UI polling and subscription refresh work lighter in Android power-save mode. */
internal object BatteryAwarePollingPolicy {
    const val NORMAL_INTERVAL_MS = 1_000L
    const val POWER_SAVE_INTERVAL_MS = 5_000L
    const val NORMAL_SUBSCRIPTION_INTERVAL_MS = 60_000L
    const val POWER_SAVE_SUBSCRIPTION_INTERVAL_MS = 5L * 60L * 1_000L

    fun interval(powerSaveEnabled: Boolean): Long =
        if (powerSaveEnabled) POWER_SAVE_INTERVAL_MS else NORMAL_INTERVAL_MS

    fun subscriptionInterval(powerSaveEnabled: Boolean): Long =
        if (powerSaveEnabled) POWER_SAVE_SUBSCRIPTION_INTERVAL_MS else NORMAL_SUBSCRIPTION_INTERVAL_MS
}
