package com.astra.vpn

import android.content.Context

/**
 * Durable transition state for banking automation. This is intentionally
 * separate from the encrypted profile snapshot and never stores a proxy URI.
 */
internal class BankingAutomationStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): BankingAutomationState {
        val raw = prefs.getString(STATE_KEY, null)
        val decoded = BankingAutomationStateCodec.decode(raw)
        if (raw != null && runCatching { BankingAutomationStateCodec.encode(decoded) }.getOrNull() != raw) {
            // Remove corrupt/unsupported data so it cannot be retried as a resume
            // instruction on every process start.
            check(prefs.edit().remove(STATE_KEY).commit()) {
                "Не удалось очистить повреждённое состояние банковской автопаузы"
            }
        }
        return decoded
    }

    @Synchronized
    fun write(state: BankingAutomationState) {
        val encoded = BankingAutomationStateCodec.encode(state)
        check(prefs.edit().putString(STATE_KEY, encoded).commit()) {
            "Не удалось сохранить состояние банковской автоматизации"
        }
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove(STATE_KEY).commit()) {
            "Не удалось очистить состояние банковской автоматизации"
        }
    }

    private companion object {
        const val PREFERENCES = "astra_settings"
        const val STATE_KEY = "banking_automation_state_v1"
    }
}
