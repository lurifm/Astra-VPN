package com.astra.vpn

/** Keeps UI polling light while Android is in power-save mode. */
internal object BatteryAwarePollingPolicy {
    const val NORMAL_INTERVAL_MS = 1_000L
    const val POWER_SAVE_INTERVAL_MS = 5_000L

    fun interval(powerSaveEnabled: Boolean): Long =
        if (powerSaveEnabled) POWER_SAVE_INTERVAL_MS else NORMAL_INTERVAL_MS
}
