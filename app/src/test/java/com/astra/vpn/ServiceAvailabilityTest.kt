package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceAvailabilityTest {
    @Test fun classifiesProbeResults() {
        assertEquals(ServiceAvailability.State.UNKNOWN, ServiceAvailability.classify(ServiceAvailability.Probe(0, 0)))
        assertEquals(ServiceAvailability.State.AVAILABLE, ServiceAvailability.classify(ServiceAvailability.Probe(2, 2)))
        assertEquals(ServiceAvailability.State.DEGRADED, ServiceAvailability.classify(ServiceAvailability.Probe(1, 2)))
        assertEquals(ServiceAvailability.State.UNAVAILABLE, ServiceAvailability.classify(ServiceAvailability.Probe(0, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImpossibleProbeCounts() {
        ServiceAvailability.Probe(3, 2)
    }

    @Test fun summarizesIncompleteChecksWithoutCallingThemAvailable() {
        assertEquals(
            "Проверка неполная: ChatGPT",
            ServiceAvailability.summary(
                mapOf(
                    "YouTube" to ServiceAvailability.State.AVAILABLE,
                    "ChatGPT" to ServiceAvailability.State.UNKNOWN
                )
            )
        )
        assertEquals(
            "Проверенные сервисы доступны",
            ServiceAvailability.summary(mapOf("YouTube" to ServiceAvailability.State.AVAILABLE))
        )
    }

    @Test fun summarizesMixedAndUnknownServiceResults() {
        assertEquals(
            "Недоступно: Telegram",
            ServiceAvailability.summary(
                mapOf(
                    "YouTube" to ServiceAvailability.State.AVAILABLE,
                    "Telegram" to ServiceAvailability.State.UNAVAILABLE,
                    "ChatGPT" to ServiceAvailability.State.UNKNOWN
                )
            )
        )
        assertEquals("Нет данных", ServiceAvailability.summary(mapOf("YouTube" to ServiceAvailability.State.UNKNOWN)))
    }
}
