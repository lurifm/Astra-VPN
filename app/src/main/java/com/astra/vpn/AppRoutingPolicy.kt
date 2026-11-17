package com.astra.vpn

/**
 * Guards Android's special empty allow-list behavior: without at least one
 * successfully applied allowed package, Android routes every app by default.
 */
internal object AppRoutingPolicy {
    fun validate(mode: String, selectedCount: Int, appliedCount: Int) {
        if (mode != "include") return
        require(selectedCount > 0) {
            "Режим «Только выбранные приложения» включён, но список пуст"
        }
        require(appliedCount > 0) {
            "Не удалось применить ни одно приложение из списка VPN"
        }
    }
}
