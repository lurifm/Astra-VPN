package com.astra.vpn

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Imports one proxy URI, a multiline subscription, or an HTTPS subscription URL.
 * Network downloads are deliberately restricted to HTTPS.
 */
data class SubscriptionMetadata(val title: String? = null, val uploadBytes: Long? = null, val downloadBytes: Long? = null, val totalBytes: Long? = null, val expiresAtEpochSeconds: Long? = null)
data class ParsedSubscription(val links: List<String>, val metadata: SubscriptionMetadata)

object SubscriptionParser {
    private data class DownloadResult(val content: String, val headers: Map<String, String>)
    // Keep imports limited to protocols that SingBoxConfigBuilder can actually launch.
    // Other URI schemes must not appear as selectable profiles until their builders exist.
    private val supportedSchemes = setOf(
        "vless", "hysteria2", "hy2", "tuic"
    )

    fun importText(input: String): List<String> = importDetailed(input).links

    fun importDetailed(input: String): ParsedSubscription = importDetailed(input, SubscriptionDownloadBudget())

    internal fun importDetailed(
        input: String,
        downloadBudget: SubscriptionDownloadBudget?
    ): ParsedSubscription {
        validateInputSize(input)
        val text = input.trim()
        require(text.isNotEmpty()) { "Пустая ссылка или подписка" }

        val downloaded = if (text.startsWith("https://", ignoreCase = true)) {
            download(text, downloadBudget)
        } else null
        val content = downloaded?.content ?: text
        val decoded = decodeIfBase64(content)
        val candidates = decoded
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                supportedSchemes.contains(line.substringBefore("://", "").lowercase()) &&
                    hasValidEndpoint(line)
            }
            .distinct()
            .toList()

        require(candidates.isNotEmpty()) {
            "В подписке не найдены поддерживаемые ссылки серверов"
        }
        require(SubscriptionImportPolicy.isWithinProfileLimit(candidates.size)) {
            "В подписке должно быть от 1 до ${SubscriptionImportPolicy.MAX_PROFILES} серверов"
        }

        // Validate every imported profile with the exact sing-box core shipped
        // in the app. This catches malformed credentials/TLS/transport options
        // before the user selects a server and presses Connect.
        val links = candidates.filter { link ->
            // Parser exceptions may contain the full proxy URI and credentials.
            // Validation only needs the success/failure result; never retain diagnostics.
            SingBoxConfigBuilder.validateProxyOutbound(link)
        }

        require(links.isNotEmpty()) {
            "Ссылки найдены, но конфигурации серверов не прошли проверку sing-box"
        }
        return ParsedSubscription(links, parseMetadata(downloaded?.headers.orEmpty()))
    }

    internal fun validateInputSize(input: String) {
        require(ImportInputPolicy.isWithinUtf8ByteLimit(input, MAX_RESPONSE_BYTES)) {
            "Текст подписки слишком большой (максимум 2 МБ)"
        }
    }

    internal fun parseMetadata(headers: Map<String, String>): SubscriptionMetadata {
        val info = headers.entries.firstOrNull { it.key.equals("subscription-userinfo", true) }?.value.orEmpty()
        val values = info.split(';').mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size != 2) return@mapNotNull null
            val key = pair[0].trim().lowercase()
            val value = pair[1].trim().toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            key to value
        }.toMap()
        // Provider-controlled headers are untrusted input. Remove control
        // characters and cap the title before it reaches persistent storage/UI.
        val rawTitle = headers.entries.firstOrNull { it.key.equals("profile-title", true) }?.value
        val title = rawTitle
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
        return SubscriptionMetadata(
            title,
            values["upload"],
            values["download"],
            values["total"],
            values["expire"]?.takeIf { it in 1..MAX_EXPIRY_EPOCH_SECONDS }
        )
    }

    /**
     * Drop malformed entries before they reach the server list. A profile must
     * contain a supported scheme, a parseable host and an explicit valid port.
     */
    private fun hasValidEndpoint(link: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(link)
        !uri.host.isNullOrBlank() && uri.port in 1..65535
    }.getOrDefault(false)

    internal fun validateSubscriptionUrl(address: String): URL {
        val url = URL(address)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Подписки разрешено загружать только по HTTPS"
        }
        require(!url.host.isNullOrBlank()) { "В ссылке подписки не указан адрес сервера" }
        require(url.userInfo == null) { "Логин и пароль в адресе подписки не допускаются" }
        require(url.port == -1 || url.port in 1..65535) { "Некорректный порт сервера подписки" }
        return url
    }

    private fun download(
        address: String,
        budget: SubscriptionDownloadBudget?
    ): DownloadResult {
        var current = validateSubscriptionUrl(address)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(current.protocol.equals("https", ignoreCase = true)) {
                "Подписки разрешено загружать только по HTTPS"
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = budget?.remainingTimeoutMillis(15_000) ?: 15_000
            connection.readTimeout = budget?.remainingTimeoutMillis(20_000) ?: 20_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "AstraVPN/1.0 Android")
            connection.instanceFollowRedirects = false

            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    require(redirectCount < MAX_REDIRECTS) { "Слишком много перенаправлений" }
                    val location = connection.getHeaderField("Location")
                        ?: error("Сервер вернул перенаправление без адреса")
                    current = validateSubscriptionUrl(URL(current, location).toExternalForm())
                    return@repeat
                }
                require(code in 200..299) { "Сервер подписки ответил: HTTP $code" }
                val bytes = connection.inputStream.use { input ->
                    val output = WipingByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    try {
                        while (true) {
                            connection.readTimeout = budget?.remainingTimeoutMillis(20_000) ?: 20_000
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_RESPONSE_BYTES) { "Подписка слишком большая" }
                            budget?.consume(count)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } finally {
                        buffer.fill(0)
                        output.wipe()
                    }
                }
                val headers = mutableMapOf<String, String>()
                listOf("subscription-userinfo", "profile-title", "profile-update-interval").forEach { name ->
                    connection.getHeaderField(name)?.let { headers[name] = it }
                }
                val content = try {
                    String(bytes, Charset.forName("UTF-8"))
                } finally {
                    bytes.fill(0)
                }
                return DownloadResult(content, headers)
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
        val flags = if ('-' in compact || '_' in compact) {
            Base64.URL_SAFE or Base64.NO_WRAP
        } else {
            Base64.DEFAULT
        }
        return runCatching {
            val decoded = Base64.decode(compact, flags)
            try {
                String(decoded, Charset.forName("UTF-8"))
            } finally {
                decoded.fill(0)
            }
        }.getOrDefault(value)
    }

    private class WipingByteArrayOutputStream : java.io.ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private const val MAX_EXPIRY_EPOCH_SECONDS = 253402300799L // 9999-12-31T23:59:59Z
    private const val MAX_RESPONSE_BYTES = ImportInputPolicy.MAX_TOTAL_BYTES
    private const val MAX_REDIRECTS = 5
}
