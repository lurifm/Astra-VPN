package com.astra.vpn

/**
 * Guards Android's special empty allow-list behavior and prevents an incomplete
 * include-list from silently routing an unselected app outside the VPN.
 */
internal object AppRoutingPolicy {
    fun validate(mode: String, selectedCount: Int, appliedCount: Int) {
        require(mode == "include" || mode == "exclude") {
            "Неизвестный режим маршрутизации приложений"
        }
        require(selectedCount >= 0 && appliedCount >= 0 && appliedCount <= selectedCount) {
            "Некорректное количество правил маршрутизации"
        }
        if (mode == "exclude") return
        require(selectedCount > 0) {
            "Режим «Только выбранные приложения» включён, но список пуст"
        }
        require(appliedCount == selectedCount) {
            "Не удалось применить все выбранные приложения. VPN не будет запущен, чтобы избежать обхода туннеля."
        }
    }
}
