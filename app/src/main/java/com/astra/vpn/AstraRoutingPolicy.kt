package com.astra.vpn

import org.json.JSONArray

/** Conservative starter domain list for the optional local-direct preset. */
internal object AstraRoutingPolicy {
    private val suffixes = listOf(
        "ru", "рф", "su",
        "yandex.ru", "vk.com", "vk.ru",
        "mail.ru", "ok.ru", "gosuslugi.ru",
        "nalog.gov.ru", "sberbank.ru"
    )

    fun localDirectSuffixes(): List<String> = suffixes.toList()

    fun localDirectSuffixesJson(): JSONArray =
        JSONArray().also { array -> suffixes.forEach(array::put) }
}
