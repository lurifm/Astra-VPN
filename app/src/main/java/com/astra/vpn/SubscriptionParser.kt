package com.astra.vpn

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Imports one proxy URI, a multiline subscription, or an HTTPS subscription URL.
 * Network downloads are deliberately restricted to HTTPS.
 */
object SubscriptionParser {
    // Keep imports limited to protocols that SingBoxConfigBuilder can actually launch.
    // Other URI schemes must not appear as selectable profiles until their builders exist.
    private val supportedSchemes = setOf(
        "vless", "hysteria2", "hy2", "tuic"
    )

    fun importText(input: String): List<String> {
        val text = input.trim()
        require(text.isNotEmpty()) { "Пустая ссылка или подписка" }

        val content = if (text.startsWith("https://", ignoreCase = true)) {
            download(text)
        } else {
            text
        }

        val decoded = decodeIfBase64(content)
        val links = decoded
            .lineSequence()
            .map { it.trim() }
            .filter { line -> supportedSchemes.contains(line.substringBefore("://", "").lowercase()) }
            .distinct()
            .toList()

        require(links.isNotEmpty()) {
            "В подписке не найдены поддерживаемые ссылки серверов"
        }
        return links
    }

    private fun download(address: String): String {
        var current = URL(address)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(current.protocol.equals("https", ignoreCase = true)) {
                "Подписки разрешено загружать только по HTTPS"
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "AstraVPN/1.0 Android")
            connection.instanceFollowRedirects = false

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    require(redirectCount < MAX_REDIRECTS) { "Слишком много перенаправлений" }
                    val location = connection.getHeaderField("Location")
                        ?: error("Сервер вернул перенаправление без адреса")
                    current = URL(current, location)
                    require(current.protocol.equals("https", ignoreCase = true)) {
                        "Небезопасное перенаправление: разрешён только HTTPS"
                    }
                    return@repeat
                }
                require(code in 200..299) { "Сервер подписки ответил: HTTP $code" }
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_RESPONSE_BYTES) { "Подписка слишком большая" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return String(bytes, Charset.forName("UTF-8"))
            } finally {
                connection.disconnect()
            }
        }
        error("Не удалось загрузить подписку")
    }

    private fun decodeIfBase64(value: String): String {
        val compact = value.trim().replace("\\s".toRegex(), "")
        if (compact.isEmpty() || compact.length % 4 == 1) return value
        val looksEncoded = compact.matches(Regex("[A-Za-z0-9_+/=-]+"))
        if (!looksEncoded) return value
        return runCatching {
            Base64.decode(compact, Base64.DEFAULT)
        }.recoverCatching {
            Base64.decode(compact, Base64.URL_SAFE or Base64.NO_WRAP)
        }.map { String(it, Charset.forName("UTF-8")) }
            .getOrDefault(value)
    }

    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
}
