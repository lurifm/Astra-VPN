package com.astra.vpn

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
        "🇧🇷" to listOf("браз", "brazil", "bra")
    )

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
        "🇮🇳" to "Индия", "🇧🇷" to "Бразилия"
    )

    fun countryNameForFlag(flag: String): String = namesByFlag[flag] ?: "Другая страна"

    fun flagFor(label: String): String {
        val normalized = label.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        if (normalized.isEmpty()) return "🌐"

        return countries.firstOrNull { (_, aliases) ->
            aliases.any { alias ->
                if (alias.length <= 3 && alias.all(Char::isLetter)) {
                    Regex("(^|\\s)${Regex.escape(alias)}($|\\s)").containsMatchIn(normalized)
                } else {
                    normalized.contains(alias)
                }
            }
        }?.first ?: "🌐"
    }
}
