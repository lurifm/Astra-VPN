package com.astra.vpn
/** Maps short natural-language requests to a known HTTPS service probe. */
internal object ServiceIntentResolver {
    private val aliases = mapOf(
        "netflix" to "Netflix", "нетфликс" to "Netflix", "netflix us" to "Netflix",
        "netflix usa" to "Netflix", "американский netflix" to "Netflix",
        "youtube" to "YouTube", "ютуб" to "YouTube",
        "telegram" to "Telegram", "телеграм" to "Telegram",
        "chatgpt" to "ChatGPT", "чатгпт" to "ChatGPT"
    )
    fun resolve(request: String): ServiceReachabilityChecker.Target? {
        val normalized = request.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
        val name = aliases.entries.sortedByDescending { it.key.length }
            .firstOrNull { normalized.contains(it.key) }?.value ?: return null
        return ServiceReachabilityChecker.targets.firstOrNull { it.name == name }
    }
}
