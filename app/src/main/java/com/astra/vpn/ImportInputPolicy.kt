package com.astra.vpn

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Resource limits shared by every user-facing configuration import path. */
internal object ImportInputPolicy {
    const val MAX_TOTAL_BYTES = 2 * 1024 * 1024
    const val MAX_FILES = 20
    const val MAX_PROFILES = SubscriptionImportPolicy.MAX_PROFILES
    const val MAX_PREVIEW_CHARS = 64 * 1024

    fun requireFileCount(count: Int) {
        require(count in 1..MAX_FILES) {
            "Можно выбрать от 1 до $MAX_FILES файлов за один раз"
        }
    }

    fun requireProfileCount(count: Int) {
        require(count in 1..MAX_PROFILES) {
            "За один импорт можно добавить не более $MAX_PROFILES серверов"
        }
    }

    fun requireStoredProfileCount(existingCount: Int, additionalCount: Int) {
        require(existingCount >= 0 && additionalCount >= 0 &&
            additionalCount <= MAX_PROFILES - existingCount) {
            "В хранилище можно сохранить не более $MAX_PROFILES серверов"
        }
    }

    fun validateTextSize(value: CharSequence) {
        require(value.length <= MAX_TOTAL_BYTES) {
            "Текст импорта слишком большой (максимум 2 МБ)"
        }
        validateTextSize(value.toString())
    }

    fun validateTextSize(value: String) {
        require(value.length <= MAX_TOTAL_BYTES &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_TOTAL_BYTES) {
            "Текст импорта слишком большой (максимум 2 МБ)"
        }
    }

    /** Reads at most [maxBytes], failing before writing any bytes beyond the cap. */
    fun readBytes(input: InputStream, maxBytes: Int = MAX_TOTAL_BYTES): ByteArray {
        require(maxBytes in 0..MAX_TOTAL_BYTES)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > maxBytes - output.size()) {
                throw ImportInputLimitException("Общий размер импортируемых файлов превышает 2 МБ")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

internal class ImportInputLimitException(message: String) : IllegalArgumentException(message)
