package com.astra.vpn

/** Small deterministic onboarding model; UI owns presentation and persistence. */
internal object OnboardingSlides {
    data class Slide(val title: String, val body: String)

    val all = listOf(
        Slide("Своя орбита. Своя скорость. Своя защита", "Добавь сервер или импортируй подписку — Astra сохранит профили в защищённом хранилище."),
        Slide("Выбирай лучший маршрут", "Кнопка «Быстрый» проверяет задержку и доступную скорость серверов, а затем предлагает подходящий вариант."),
        Slide("Контролируй соединение", "Следи за состоянием VPN, трафиком и диагностикой. Для системной блокировки без VPN включи Always-on VPN и lockdown в настройках Android.")
    )

    fun next(index: Int): Int? = if (index in all.indices && index < all.lastIndex) index + 1 else null
}
