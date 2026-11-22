package com.astra.vpn

/** Shared, truthful status wording for Android surfaces outside the main Activity. */
internal object VpnSurfaceStatusPolicy {
    fun tileContentDescription(state: String?): String = when (state) {
        AstraVpnService.STATE_CONNECTED -> "VPN подключён. Нажмите для отключения"
        AstraVpnService.STATE_CONNECTING -> "VPN подключается. Нажмите, чтобы открыть Astra VPN"
        AstraVpnService.STATE_ERROR -> "Ошибка подключения VPN. Нажмите, чтобы открыть Astra VPN"
        else -> "VPN отключён. Нажмите для подключения"
    }

    fun tileStateDescription(state: String?): String = when (state) {
        AstraVpnService.STATE_CONNECTED -> "Подключено"
        AstraVpnService.STATE_CONNECTING -> "Подключение…"
        AstraVpnService.STATE_ERROR -> "Ошибка"
        else -> "Отключено"
    }

    fun notificationErrorDetail(errorMessage: String?): String =
        errorMessage?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > 160) it.take(157) + "…" else it }
            ?: "Не удалось установить соединение"
}
