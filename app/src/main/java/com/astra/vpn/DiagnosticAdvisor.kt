package com.astra.vpn

/** Converts observable Android/VPN state into practical, non-invasive advice. */
internal object DiagnosticAdvisor {
    data class Facts(
        val hasNetwork: Boolean,
        val hasInternetCapability: Boolean,
        val validated: Boolean,
        val vpnActive: Boolean,
        val tunnelInternetOk: Boolean?,
        val captivePortal: Boolean,
        val dnsServerCount: Int
    )

    fun recommendations(facts: Facts): List<String> = buildList {
        if (!facts.hasNetwork || !facts.hasInternetCapability) {
            add("Нет активного подключения к сети. Проверь Wi-Fi или мобильные данные.")
        } else if (facts.captivePortal) {
            add("Сеть требует входа через страницу авторизации. Открой браузер и заверши вход в публичную сеть.")
        } else if (!facts.validated) {
            add("Android пока не подтвердил доступ в интернет. Проверь сигнал, баланс и ограничения сети.")
        }
        if (facts.vpnActive && facts.tunnelInternetOk == false) {
            add("VPN-туннель активен, но проверка интернета не прошла. Попробуй другой сервер или протокол.")
        } else if (!facts.vpnActive) {
            add("VPN сейчас не активен. Подключись к выбранной орбите для проверки туннеля.")
        }
        if (facts.vpnActive && facts.dnsServerCount == 0) {
            add("Android не сообщил DNS-серверы активной сети. Это не доказывает утечку, но стоит проверить настройки DNS.")
        }
        if (isEmpty()) add("Критичных признаков не обнаружено. Это базовая диагностика Android, а не гарантия отсутствия утечек.")
    }
}
