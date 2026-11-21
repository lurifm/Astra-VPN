package com.astra.vpn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class ServiceIntentResolverTest {
    @Test fun resolvesServiceNamesAndNaturalLanguage() {
        assertEquals("Netflix", ServiceIntentResolver.resolve("Хочу смотреть Netflix США")?.name)
        assertEquals("Netflix", ServiceIntentResolver.resolve("нетфликс")?.name)
        assertEquals("YouTube", ServiceIntentResolver.resolve("хочу открыть ютуб")?.name)
        assertEquals("Telegram", ServiceIntentResolver.resolve("телеграм")?.name)
        assertEquals("ChatGPT", ServiceIntentResolver.resolve("ChatGPT")?.name)
    }
    @Test fun doesNotGuessUnknownServices() {
        assertNull(ServiceIntentResolver.resolve("хочу посмотреть неизвестный сервис"))
    }
}
