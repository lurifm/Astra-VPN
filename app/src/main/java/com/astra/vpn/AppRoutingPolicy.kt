package com.astra.vpn

/**
 * Prevents incomplete per-app routing rules from silently changing which
 * applications use the VPN in either include or exclude mode.
 */
internal object AppRoutingPolicy {
    fun validate(mode: String, selectedCount: Int, appliedCount: Int) {
        require(mode == "include" || mode == "exclude") {
            "Неизвестный режим маршрутизации приложений"
        }
        require(selectedCount >= 0 && appliedCount >= 0 && appliedCount <= selectedCount) {
            "Некорректное количество правил маршрутизации"
        }
        if (mode == "include") {
            require(selectedCount > 0) {
                "Режим «Только выбранные приложения» включён, но список пуст"
            }
        }
        require(appliedCount == selectedCount) {
            "Не удалось применить все правила маршрутизации приложений. VPN не будет запущен с неполной конфигурацией."
        }
    }
}
