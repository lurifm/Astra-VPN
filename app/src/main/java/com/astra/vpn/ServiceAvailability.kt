package com.astra.vpn

/** Conservative service health classification from independent HTTPS probes. */
internal object ServiceAvailability {
    enum class State { AVAILABLE, DEGRADED, UNAVAILABLE, UNKNOWN }
    data class Probe(val successful: Int, val attempted: Int) {
        init { require(attempted >= 0 && successful in 0..attempted) }
    }

    fun classify(probe: Probe): State = when {
        probe.attempted == 0 -> State.UNKNOWN
        probe.successful == probe.attempted -> State.AVAILABLE
        probe.successful == 0 -> State.UNAVAILABLE
        else -> State.DEGRADED
    }

    fun summary(states: Map<String, State>): String {
        if (states.isEmpty() || states.values.all { it == State.UNKNOWN }) return "Нет данных"
        val unavailable = states.filterValues { it == State.UNAVAILABLE }.keys
        val degraded = states.filterValues { it == State.DEGRADED }.keys
        val unknown = states.filterValues { it == State.UNKNOWN }.keys
        return when {
            unavailable.isNotEmpty() -> "Недоступно: ${unavailable.joinToString()}"
            degraded.isNotEmpty() -> "Частично доступно: ${degraded.joinToString()}"
            unknown.isNotEmpty() -> "Проверка неполная: ${unknown.joinToString()}"
            else -> "Проверенные сервисы доступны"
        }
    }
}
