package com.astra.vpn

import java.util.Locale

/**
 * Resolves a display flag only from an explicit country name or ISO-style alias
 * in the profile label. It deliberately does not infer a server's physical
 * location from its hostname or the device's own location.
 */
object CountryFlagResolver {
    private val countries = listOf(
        "🇸🇪" to listOf("швец", "sweden", "swe"),
        "🇳🇱" to listOf("нидер", "голланд", "netherlands", "holland", "nld"),
        "🇬🇧" to listOf("британ", "великобрит", "united kingdom", "england", "uk", "gb", "gbr"),
        "🇩🇪" to listOf("герман", "germany", "deu"),
        "🇫🇷" to listOf("франц", "france", "fra"),
        "🇷🇺" to listOf("росс", "russia", "rus"),
        "🇫🇮" to listOf("финлянд", "finland", "fin"),
        "🇵🇱" to listOf("польш", "poland", "pol"),
        "🇹🇷" to listOf("турц", "turkey", "tur"),
        "🇺🇸" to listOf("сша", "united states", "america", "us", "usa"),
        "🇨🇦" to listOf("канад", "canada", "can"),
        "🇨🇭" to listOf("швейцар", "switzerland", "che"),
        "🇦🇹" to listOf("австри", "austria", "aut"),
        "🇮🇹" to listOf("итал", "italy", "ita"),
        "🇪🇸" to listOf("испани", "spain", "esp"),
        "🇳🇴" to listOf("норвег", "norway", "nor"),
        "🇩🇰" to listOf("дани", "denmark", "dnk"),
        "🇧🇪" to listOf("бельги", "belgium", "bel"),
        "🇨🇿" to listOf("чех", "czech", "cze"),
        "🇸🇰" to listOf("словаки", "slovakia", "svk"),
        "🇭🇺" to listOf("венгр", "hungary", "hun"),
        "🇷🇴" to listOf("румын", "romania", "rou"),
        "🇧🇬" to listOf("болгар", "bulgaria", "bgr"),
        "🇪🇪" to listOf("эстон", "estonia", "est"),
        "🇱🇻" to listOf("латви", "latvia", "lva"),
        "🇱🇹" to listOf("литв", "lithuania", "ltu"),
        "🇺🇦" to listOf("украин", "ukraine", "ukr"),
        "🇰🇿" to listOf("казах", "kazakhstan", "kaz"),
        "🇯🇵" to listOf("япони", "japan", "jpn"),
        "🇸🇬" to listOf("сингапур", "singapore", "sgp"),
        "🇰🇷" to listOf("коре", "south korea", "kor"),
        "🇭🇰" to listOf("гонконг", "hong kong", "hkg"),
        "🇦🇪" to listOf("оаэ", "uae", "emirates"),
        "🇮🇳" to listOf("инд", "india", "ind"),
        "🇧🇷" to listOf("браз", "brazil", "bra"),
        "🇵🇹" to listOf("португал", "portugal", "prt"),
        "🇬🇷" to listOf("грец", "greece", "grc"),
        "🇨🇾" to listOf("кипр", "cyprus", "cyp"),
        "🇦🇺" to listOf("австрал", "australia", "aus"),
        "🇳🇿" to listOf("новая зеланд", "new zealand", "nzl"),
        "🇨🇳" to listOf("кита", "china", "chn", "cn"),
        "🇹🇼" to listOf("тайван", "taiwan", "twn"),
        "🇮🇩" to listOf("индонез", "indonesia", "idn"),
        "🇹🇭" to listOf("таиланд", "thailand", "tha"),
        "🇻🇳" to listOf("вьетнам", "vietnam", "vnm"),
        "🇲🇾" to listOf("малайз", "malaysia", "mys"),
        "🇮🇱" to listOf("израил", "israel", "isr"),
        "🇲🇽" to listOf("мексик", "mexico", "mex"),
        "🇦🇷" to listOf("аргентин", "argentina", "arg"),
        "🇨🇱" to listOf("чили", "chile", "chl"),
        "🇿🇦" to listOf("южная африк", "south africa", "zaf")
    )

    // Compile token-boundary matchers once: flag resolution runs for every
    // server row whenever the profile list is rendered.
    private val separatorRegex = Regex("[^\\p{L}\\p{N}]+")
    private val compiledCountries = countries.map { (flag, aliases) ->
        flag to aliases.map { alias ->
            val tokenRegex = if (alias.length <= 3 && alias.all(Char::isLetter)) {
                Regex("(^|\\s)${Regex.escape(alias)}($|\\s)")
            } else {
                null
            }
            alias to tokenRegex
        }
    }
    private val namesByFlag = mapOf(
        "🇸🇪" to "Швеция", "🇳🇱" to "Нидерланды", "🇬🇧" to "Великобритания",
        "🇩🇪" to "Германия", "🇫🇷" to "Франция", "🇷🇺" to "Россия",
        "🇫🇮" to "Финляндия", "🇵🇱" to "Польша", "🇹🇷" to "Турция",
        "🇺🇸" to "США", "🇨🇦" to "Канада", "🇨🇭" to "Швейцария",
        "🇦🇹" to "Австрия", "🇮🇹" to "Италия", "🇪🇸" to "Испания",
        "🇳🇴" to "Норвегия", "🇩🇰" to "Дания", "🇧🇪" to "Бельгия",
        "🇨🇿" to "Чехия", "🇸🇰" to "Словакия", "🇭🇺" to "Венгрия",
        "🇷🇴" to "Румыния", "🇧🇬" to "Болгария", "🇪🇪" to "Эстония",
        "🇱🇻" to "Латвия", "🇱🇹" to "Литва", "🇺🇦" to "Украина",
        "🇰🇿" to "Казахстан", "🇯🇵" to "Япония", "🇸🇬" to "Сингапур",
        "🇰🇷" to "Южная Корея", "🇭🇰" to "Гонконг", "🇦🇪" to "ОАЭ",
        "🇮🇳" to "Индия", "🇧🇷" to "Бразилия",
        "🇵🇹" to "Португалия", "🇬🇷" to "Греция", "🇨🇾" to "Кипр",
        "🇦🇺" to "Австралия", "🇳🇿" to "Новая Зеландия", "🇨🇳" to "Китай",
        "🇹🇼" to "Тайвань", "🇮🇩" to "Индонезия", "🇹🇭" to "Таиланд",
        "🇻🇳" to "Вьетнам", "🇲🇾" to "Малайзия", "🇮🇱" to "Израиль",
        "🇲🇽" to "Мексика", "🇦🇷" to "Аргентина", "🇨🇱" to "Чили",
        "🇿🇦" to "ЮАР"
    )

    private val codesByFlag = mapOf(
        "🇸🇪" to "SE", "🇳🇱" to "NL", "🇬🇧" to "GB", "🇩🇪" to "DE",
        "🇫🇷" to "FR", "🇷🇺" to "RU", "🇫🇮" to "FI", "🇵🇱" to "PL",
        "🇹🇷" to "TR", "🇺🇸" to "US", "🇨🇦" to "CA", "🇨🇭" to "CH",
        "🇦🇹" to "AT", "🇮🇹" to "IT", "🇪🇸" to "ES", "🇳🇴" to "NO",
        "🇩🇰" to "DK", "🇧🇪" to "BE", "🇨🇿" to "CZ", "🇸🇰" to "SK",
        "🇭🇺" to "HU", "🇷🇴" to "RO", "🇧🇬" to "BG", "🇪🇪" to "EE",
        "🇱🇻" to "LV", "🇱🇹" to "LT", "🇺🇦" to "UA", "🇰🇿" to "KZ",
        "🇯🇵" to "JP", "🇸🇬" to "SG", "🇰🇷" to "KR", "🇭🇰" to "HK",
        "🇦🇪" to "AE", "🇮🇳" to "IN", "🇧🇷" to "BR", "🇵🇹" to "PT",
        "🇬🇷" to "GR", "🇨🇾" to "CY", "🇦🇺" to "AU", "🇳🇿" to "NZ",
        "🇨🇳" to "CN", "🇹🇼" to "TW", "🇮🇩" to "ID", "🇹🇭" to "TH",
        "🇻🇳" to "VN", "🇲🇾" to "MY", "🇮🇱" to "IL", "🇲🇽" to "MX",
        "🇦🇷" to "AR", "🇨🇱" to "CL", "🇿🇦" to "ZA"
    )

    fun countryCodeForFlag(flag: String): String? = codesByFlag[flag]

    fun countryNameForFlag(flag: String): String = namesByFlag[flag] ?: "Другая страна"

    fun flagFor(label: String): String {
        val normalized = label.lowercase(Locale.ROOT)
            .replace(separatorRegex, " ")
            .trim()
        if (normalized.isEmpty()) return "🌐"

        return compiledCountries.firstOrNull { (_, aliases) ->
            aliases.any { (alias, tokenRegex) ->
                tokenRegex?.containsMatchIn(normalized) ?: normalized.contains(alias)
            }
        }?.first ?: "🌐"
    }
}
