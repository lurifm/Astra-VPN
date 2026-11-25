package com.astra.vpn

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Resource limits shared by every user-facing configuration import path. */
internal object ImportInputPolicy {
    const val MAX_TOTAL_BYTES = 2 * 1024 * 1024
    const val MAX_FILES = 20
    const val MAX_PROFILES = SubscriptionImportPolicy.MAX_PROFILES
    const val MAX_PREVIEW_CHARS = 64 * 1024

    fun hasNonBlankText(value: CharSequence?): Boolean = !value.isNullOrBlank()

    fun looksLikeSupportedInput(value: String): Boolean = value.lineSequence().any { line ->
        val item = line.trim()
        item.startsWith("vless://", true) ||
            item.startsWith("hysteria2://", true) ||
            item.startsWith("hy2://", true) ||
            item.startsWith("tuic://", true) ||
            item.startsWith("https://", true)
    }

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

    fun requireFileProfiles(fileNumber: Int, profiles: List<String>) {
        require(profiles.isNotEmpty()) {
            "В файле $fileNumber не найдены поддерживаемые серверы"
        }
    }

    /** Cheap UI-thread guard; UTF-8 byte validation must run on a worker. */
    fun requireTextLength(value: CharSequence) {
        require(value.length <= MAX_TOTAL_BYTES) {
            "Текст импорта слишком большой (максимум 2 МБ)"
        }
    }

    fun isWithinUtf8ByteLimit(value: String, limit: Int): Boolean {
        require(limit in 0..MAX_TOTAL_BYTES)
        return value.length <= limit && utf8ByteCountWithinLimit(value, limit)
    }

    fun validateTextSize(value: String) {
        require(isWithinUtf8ByteLimit(value, MAX_TOTAL_BYTES)) {
            "Текст импорта слишком большой (максимум 2 МБ)"
        }
    }

    /** Counts UTF-8 bytes without allocating a second copy of potentially secret import text. */
    private fun utf8ByteCountWithinLimit(value: String, limit: Int): Boolean {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val charBytes = when {
                current.code <= 0x7F -> 1
                current.code <= 0x7FF -> 2
                current.isHighSurrogate() && index + 1 < value.length &&
                    value[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                current.isSurrogate() -> 1 // Matches the single-byte replacement used by String UTF-8 encoding.
                else -> 3
            }
            if (bytes > limit - charBytes) return false
            bytes += charBytes
            index++
        }
        return true
    }

    /** Reads at most [maxBytes], failing before writing any bytes beyond the cap. */
    fun readBytes(input: InputStream, maxBytes: Int = MAX_TOTAL_BYTES): ByteArray {
        require(maxBytes in 0..MAX_TOTAL_BYTES)
        val output = WipingImportBuffer(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    // Defensive fallback for unusual streams that report no progress
                    // from a non-empty bulk read. Avoid an infinite import loop.
                    val singleByte = input.read()
                    if (singleByte < 0) break
                    if (output.size() >= maxBytes) {
                        throw ImportInputLimitException("Общий размер импортируемых файлов превышает 2 МБ")
                    }
                    output.write(singleByte)
                    continue
                }
                if (count > maxBytes - output.size()) {
                    throw ImportInputLimitException("Общий размер импортируемых файлов превышает 2 МБ")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private class WipingImportBuffer(initialSize: Int) : ByteArrayOutputStream(initialSize) {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }
}

internal class ImportInputLimitException(message: String) : IllegalArgumentException(message)
