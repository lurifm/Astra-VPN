package com.astra.vpn

import java.util.Locale

/** Interprets the live device-wide counters without pretending they are VPN-only throughput. */
internal object LiveSpeedDiagnostics {
    fun summary(downloadMbps: Double, uploadMbps: Double, vpnConnected: Boolean, sampleAgeMs: Long): String {
        if (sampleAgeMs > 5_000L) return "Диагностика скорости: ожидание свежего замера"
        if (downloadMbps < 0.0 || uploadMbps < 0.0) return "Диагностика скорости: счётчики недоступны"
        val activity = when {
            downloadMbps >= 1.0 || uploadMbps >= 0.5 -> "Передача данных активна"
            else -> "Сейчас трафик почти не передаётся"
        }
        val route = if (vpnConnected) "VPN включён" else "VPN выключен"
        return String.format(Locale.US, "%s · %s · ↓ %.2f / ↑ %.2f Мбит/с", route, activity, downloadMbps, uploadMbps)
    }
}

/** Evaluates a manually measured VPN speed against a no-VPN baseline. */
internal object SpeedRetentionCalculator {
    fun percentage(baselineMbps: Double, vpnMbps: Double): Int? {
        if (!baselineMbps.isFinite() || !vpnMbps.isFinite() || baselineMbps <= 0.0 || vpnMbps < 0.0) return null
        return ((vpnMbps / baselineMbps) * 100.0).toInt().coerceIn(0, 1000)
    }

    fun meetsTarget(baselineMbps: Double, vpnMbps: Double, targetPercent: Int = 80): Boolean? =
        percentage(baselineMbps, vpnMbps)?.let { it >= targetPercent.coerceIn(1, 100) }
}
