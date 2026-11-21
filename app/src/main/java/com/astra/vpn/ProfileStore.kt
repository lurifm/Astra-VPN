package com.astra.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import java.security.InvalidKeyException
import java.nio.BufferUnderflowException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores imported proxy links and subscription source URLs encrypted with
 * Android Keystore AES-GCM. Subscription replacement is committed atomically:
 * a failed download/parse never removes the last known-good server list.
 */
class ProfileStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class BankPauseSnapshot(
    val profileLink: String,
    val pausedAtEpochMillis: Long
)

data class SubscriptionSummary(
    val sourceUrl: String,
    val title: String,
    val serverCount: Int,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochSeconds: Long? = null
)

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("astra_profiles", Context.MODE_PRIVATE)

    // Profile links are decrypted with Android Keystore. Cache the immutable
    // snapshot so UI refreshes (search, ping updates, favorites) do not repeat
    // expensive cryptographic work on the main thread.
    @Volatile private var profilesCache: List<String>? = null
    @Volatile private var summariesCache: List<SubscriptionSummary>? = null

    fun all(): List<String> = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        profilesCache ?: prefs.all.entries
            .filter { it.key.startsWith(PROFILE_PREFIX) || it.key.startsWith(SUBSCRIPTION_PROFILE_PREFIX) }
            .sortedBy { it.key }
            .map { (_, value) -> decryptStored(value as String) }
            .distinct()
            .also { profilesCache = it }
    }

    /**
     * Keeps only the selected proxy URI for opt-in process recovery.
     * The URI is encrypted with the same Android Keystore AES-GCM key as profiles.
     */
    fun setRecoveryProfile(link: String?) = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val editor = prefs.edit()
        if (link.isNullOrBlank()) {
            editor.remove(RECOVERY_PROFILE_KEY)
        } else {
            require(all().contains(link)) { "Профиль для восстановления не найден" }
            editor.putString(RECOVERY_PROFILE_KEY, encrypt(link))
        }
        check(editor.commit()) { "Не удалось сохранить профиль восстановления" }
    }

    fun recoveryProfile(): String? = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val encrypted = prefs.getString(RECOVERY_PROFILE_KEY, null) ?: return@synchronized null
        val link = decryptStored(encrypted)
        if (link !in all()) {
            check(prefs.edit().remove(RECOVERY_PROFILE_KEY).commit()) {
                "Не удалось очистить устаревший профиль восстановления"
            }
            return@synchronized null
        }
        link
    }

    /**
     * Persists the UI selection separately from opt-in VPN process recovery.
     * The complete proxy URI is encrypted with Android Keystore; credentials
     * are never written to plaintext preferences.
     */
    /**
     * Stores the exact profile that was active when banking automation paused the tunnel.
     * The proxy URI remains encrypted; timestamp is non-sensitive local state.
     */
    fun setBankPauseSnapshot(profileLink: String?, pausedAtEpochMillis: Long = System.currentTimeMillis()) =
        synchronized(SUBSCRIPTION_MUTATION_LOCK) {
            val editor = prefs.edit()
            if (profileLink.isNullOrBlank()) {
                editor.remove(BANK_PAUSE_PROFILE_KEY).remove(BANK_PAUSE_AT_KEY)
            } else {
                require(all().contains(profileLink)) { "Профиль для автоматического восстановления не найден" }
                require(pausedAtEpochMillis > 0L) { "Некорректное время автоматической паузы" }
                editor.putString(BANK_PAUSE_PROFILE_KEY, encrypt(profileLink))
                    .putLong(BANK_PAUSE_AT_KEY, pausedAtEpochMillis)
            }
            check(editor.commit()) { "Не удалось сохранить состояние автоматической паузы" }
        }

    fun bankPauseSnapshot(): BankPauseSnapshot? = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val encrypted = prefs.getString(BANK_PAUSE_PROFILE_KEY, null) ?: return@synchronized null
        val link = decryptStored(encrypted)
        val pausedAt = prefs.getLong(BANK_PAUSE_AT_KEY, 0L)
        if (pausedAt <= 0L || link !in all()) return@synchronized null
        BankPauseSnapshot(link, pausedAt)
    }

    fun clearBankPauseSnapshot() = setBankPauseSnapshot(null)

    fun setSelectedProfile(link: String?) = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val current = prefs.getString(SELECTED_PROFILE_KEY, null)?.let(::decryptStored)
        if (current == link) return@synchronized
        val editor = prefs.edit()
        if (link.isNullOrBlank()) {
            editor.remove(SELECTED_PROFILE_KEY)
        } else {
            editor.putString(SELECTED_PROFILE_KEY, encrypt(link))
        }
        check(editor.commit()) { "Не удалось сохранить выбранный сервер" }
    }

    /**
     * Restores the saved selection. Subscription links may rotate between
     * launches, so use the same credential-agnostic matcher as live refresh.
     * A removed or ambiguous profile is discarded rather than guessed.
     */
    fun selectedProfile(): String? = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val encrypted = prefs.getString(SELECTED_PROFILE_KEY, null) ?: return@synchronized null
        val saved = decryptStored(encrypted)
        val available = all()
        val resolved = if (saved in available) saved else ProfileSelectionMatcher.remap(saved, available)
        if (resolved == null) {
            check(prefs.edit().remove(SELECTED_PROFILE_KEY).commit()) {
                "Не удалось очистить устаревший выбор сервера"
            }
            return@synchronized null
        }
        if (resolved != saved) {
            check(prefs.edit().putString(SELECTED_PROFILE_KEY, encrypt(resolved)).commit()) {
                "Не удалось обновить выбранный сервер"
            }
        }
        resolved
    }

    fun add(link: String) = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val id = java.util.UUID.randomUUID().toString()
        check(prefs.edit().putString(PROFILE_PREFIX + id, encrypt(link)).commit()) {
            "Не удалось сохранить сервер"
        }
        profilesCache = null
    }

    fun addAll(links: List<String>) = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val existing = all().toSet()
        val editor = prefs.edit()
        links.distinct().filterNot { it in existing }.forEach { link ->
            editor.putString(PROFILE_PREFIX + java.util.UUID.randomUUID().toString(), encrypt(link))
        }
        check(editor.commit()) { "Не удалось сохранить список серверов" }
        profilesCache = null
    }

    /** Replace only the profiles owned by this HTTPS subscription. */
    fun replaceSubscription(sourceUrl: String, links: List<String>, metadata: SubscriptionMetadata = SubscriptionMetadata()) =
        synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        require(sourceUrl.startsWith("https://", ignoreCase = true)) {
            "Источник подписки должен использовать HTTPS"
        }
        require(links.isNotEmpty()) { "Подписка не содержит серверов" }
        val sourceId = sourceId(sourceUrl)
        val profilePrefix = SUBSCRIPTION_PROFILE_PREFIX + sourceId + "_"
        val sourceKey = SUBSCRIPTION_SOURCE_PREFIX + sourceId
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(profilePrefix) }.forEach(editor::remove)
        links.distinct().forEachIndexed { index, link ->
            editor.putString(SubscriptionProfileKey.storageKey(sourceId, index, link), encrypt(link))
        }
        editor.putString(sourceKey, encrypt(sourceUrl))
        val metadataKey = SUBSCRIPTION_META_PREFIX + sourceId
        editor.putString(metadataKey, SubscriptionMetadataCodec.encode(metadata))
        // Provider metadata and the user's custom label are stored separately.
        // Missing title headers must not erase a name the user chose themselves.
        metadata.title?.let { editor.putString(SUBSCRIPTION_PROVIDER_TITLE_PREFIX + sourceId, it.take(120)) }
        check(editor.commit()) { "Не удалось сохранить обновлённую подписку" }
        profilesCache = null
        summariesCache = null
    }

    fun renameSubscription(sourceUrl: String, title: String) =
        synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val id = sourceId(sourceUrl)
        val sourceKey = SUBSCRIPTION_SOURCE_PREFIX + id
        require(prefs.contains(sourceKey)) { "Подписка не найдена" }
        val cleanTitle = title.trim().take(80)
        require(cleanTitle.isNotEmpty()) { "Название не может быть пустым" }
        check(prefs.edit().putString(SUBSCRIPTION_CUSTOM_TITLE_PREFIX + id, cleanTitle).commit()) {
            "Не удалось сохранить название подписки"
        }
        summariesCache = null
    }

    /** Remove a subscription and all profiles owned by it; return removed links. */
    fun removeSubscription(sourceUrl: String): List<String> =
        synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val id = sourceId(sourceUrl)
        val profilePrefix = SUBSCRIPTION_PROFILE_PREFIX + id + "_"
        val sourceKey = SUBSCRIPTION_SOURCE_PREFIX + id
        val removed = prefs.all.entries.filter { it.key.startsWith(profilePrefix) }
            .map { (_, value) -> decryptStored(value as String) }
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(profilePrefix) }.forEach(editor::remove)
        editor.remove(sourceKey)
            .remove(SUBSCRIPTION_META_PREFIX + id)
            .remove(SUBSCRIPTION_TITLE_PREFIX + id)
            .remove(SUBSCRIPTION_CUSTOM_TITLE_PREFIX + id)
            .remove(SUBSCRIPTION_PROVIDER_TITLE_PREFIX + id)
        check(editor.commit()) { "Не удалось удалить подписку" }
        profilesCache = null
        summariesCache = null
        return removed
    }

    fun subscriptionSources(): List<String> = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        prefs.all.entries
            .filter { it.key.startsWith(SUBSCRIPTION_SOURCE_PREFIX) }
            .map { (_, value) -> decryptStored(value as String) }
            .distinct()
    }

    /** Safe local summary; usage and expiry are displayed only when provider metadata exists. */
    fun subscriptionSummaries(): List<SubscriptionSummary> = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        summariesCache ?: run {
            // Read one SharedPreferences snapshot instead of repeatedly copying
            // the complete map while counting every subscription's profiles.
            val snapshot = prefs.all
            val profileCounts = HashMap<String, Int>()
            snapshot.keys.forEach { key ->
                if (key.startsWith(SUBSCRIPTION_PROFILE_PREFIX)) {
                    val remainder = key.removePrefix(SUBSCRIPTION_PROFILE_PREFIX)
                    val separator = remainder.indexOf('_')
                    if (separator > 0) {
                        val sourceId = remainder.substring(0, separator)
                        profileCounts[sourceId] = (profileCounts[sourceId] ?: 0) + 1
                    }
                }
            }
            snapshot.entries
                .filter { it.key.startsWith(SUBSCRIPTION_SOURCE_PREFIX) }
                .map { (key, value) ->
                    val source = decryptStored(value as String)
                    val id = key.removePrefix(SUBSCRIPTION_SOURCE_PREFIX)
                    val host = runCatching { android.net.Uri.parse(source).host }.getOrNull()
                    val usage = SubscriptionMetadataCodec.decode(
                        snapshot[SUBSCRIPTION_META_PREFIX + id] as? String
                    )
                    val title = snapshot[SUBSCRIPTION_CUSTOM_TITLE_PREFIX + id] as? String
                        ?: snapshot[SUBSCRIPTION_TITLE_PREFIX + id] as? String // legacy custom/provider title
                        ?: snapshot[SUBSCRIPTION_PROVIDER_TITLE_PREFIX + id] as? String
                        ?: host?.takeIf { it.isNotBlank() } ?: "Подписка"
                    SubscriptionSummary(
                        source,
                        title,
                        profileCounts[id] ?: 0,
                        usage.uploadBytes,
                        usage.downloadBytes,
                        usage.totalBytes,
                        usage.expiresAtEpochSeconds
                    )
                }
                .sortedBy { it.title.lowercase(java.util.Locale.getDefault()) }
                .also { summariesCache = it }
        }
    }

    fun remove(link: String) = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        // Only profile values are encrypted proxy links. Subscription metadata
        // is stored as plain encoded strings and must never enter decryptStored().
        val entry = prefs.all.entries.firstOrNull { (key, value) ->
            (key.startsWith(PROFILE_PREFIX) || key.startsWith(SUBSCRIPTION_PROFILE_PREFIX)) &&
                decryptStored(value as String) == link
        } ?: return@synchronized
        check(prefs.edit().remove(entry.key).commit()) { "Не удалось удалить сервер" }
        profilesCache = null
        summariesCache = null
    }

    /**
     * Build a portable JSON snapshot. Only profile/subscription data is included;
     * Android Keystore ciphertext, device keys and transient runtime state are excluded.
     */
    fun exportBackupPayload(): ByteArray = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        val snapshot = prefs.all
        val root = org.json.JSONObject().put("schemaVersion", 1)
        val manualProfiles = org.json.JSONArray()
        snapshot.entries
            .filter { it.key.startsWith(PROFILE_PREFIX) }
            .sortedBy { it.key }
            .forEach { (_, value) -> manualProfiles.put(decryptStored(value as String)) }
        root.put("profiles", manualProfiles)

        val subscriptions = org.json.JSONArray()
        snapshot.entries
            .filter { it.key.startsWith(SUBSCRIPTION_SOURCE_PREFIX) }
            .sortedBy { it.key }
            .forEach { (sourceKey, encryptedSource) ->
                val id = sourceKey.removePrefix(SUBSCRIPTION_SOURCE_PREFIX)
                val prefix = SUBSCRIPTION_PROFILE_PREFIX + id + "_"
                val links = org.json.JSONArray()
                snapshot.entries
                    .filter { it.key.startsWith(prefix) }
                    .sortedBy { it.key }
                    .forEach { (_, value) -> links.put(decryptStored(value as String)) }
                val item = org.json.JSONObject()
                    .put("sourceUrl", decryptStored(encryptedSource as String))
                    .put("profiles", links)
                    .put("metadata", snapshot[SUBSCRIPTION_META_PREFIX + id] as? String ?: "")
                (snapshot[SUBSCRIPTION_CUSTOM_TITLE_PREFIX + id] as? String
                    ?: snapshot[SUBSCRIPTION_TITLE_PREFIX + id] as? String)?.let {
                    item.put("customTitle", it)
                }
                (snapshot[SUBSCRIPTION_PROVIDER_TITLE_PREFIX + id] as? String)?.let {
                    item.put("providerTitle", it)
                }
                subscriptions.put(item)
            }
        root.put("subscriptions", subscriptions)
        root.toString().toByteArray(Charsets.UTF_8).also {
            require(it.size in 1..EncryptedBackupCodec.MAX_PLAINTEXT_BYTES) {
                "Резервная копия слишком большая"
            }
        }
    }

    /**
     * Merge standalone profiles and atomically replace subscription groups that
     * share the same HTTPS source. All links are encrypted before one commit.
     */
    fun restoreBackupPayload(payload: ByteArray): Int = synchronized(SUBSCRIPTION_MUTATION_LOCK) {
        require(payload.isNotEmpty() && payload.size <= EncryptedBackupCodec.MAX_PLAINTEXT_BYTES) {
            "Недопустимый размер резервной копии"
        }
        val root = org.json.JSONObject(String(payload, Charsets.UTF_8))
        require(root.optInt("schemaVersion", -1) == 1) { "Версия данных резервной копии не поддерживается" }
        val profileArray = root.optJSONArray("profiles")
            ?: throw IllegalArgumentException("В резервной копии отсутствует список серверов")
        val subscriptionArray = root.optJSONArray("subscriptions")
            ?: throw IllegalArgumentException("В резервной копии отсутствует список подписок")
        require(profileArray.length() <= MAX_BACKUP_PROFILES) { "Слишком много серверов в резервной копии" }
        require(subscriptionArray.length() <= MAX_BACKUP_SUBSCRIPTIONS) { "Слишком много подписок в резервной копии" }

        fun readLinks(array: org.json.JSONArray): List<String> {
            require(array.length() in 1..MAX_BACKUP_PROFILES) { "Пустая или слишком большая группа серверов" }
            return (0 until array.length()).map { index ->
                val link = array.optString(index).trim()
                require(isSupportedBackupLink(link)) { "В резервной копии найден неподдерживаемый адрес сервера" }
                link
            }.distinct()
        }

        val manualLinks = if (profileArray.length() == 0) emptyList() else
            (0 until profileArray.length()).map { index ->
                profileArray.optString(index).trim().also {
                    require(isSupportedBackupLink(it)) { "В резервной копии найден неподдерживаемый адрес сервера" }
                }
            }.distinct()
        val subscriptions = (0 until subscriptionArray.length()).map { index ->
            val item = subscriptionArray.optJSONObject(index)
                ?: throw IllegalArgumentException("Повреждена запись подписки в резервной копии")
            val source = item.optString("sourceUrl").trim()
            require(source.startsWith("https://", ignoreCase = true) && android.net.Uri.parse(source).host != null) {
                "В резервной копии найден некорректный адрес HTTPS-подписки"
            }
            val links = readLinks(item.optJSONArray("profiles")
                ?: throw IllegalArgumentException("В резервной копии у подписки отсутствуют серверы"))
            val metadata = item.optString("metadata", "")
            require(metadata.length <= 256) { "Повреждены метаданные подписки" }
            val customTitle = item.optString("customTitle").takeIf { it.isNotBlank() }?.take(80)
            val providerTitle = item.optString("providerTitle").takeIf { it.isNotBlank() }?.take(120)
            Triple(source, links, Triple(metadata, customTitle, providerTitle))
        }
        val totalLinks = manualLinks.size + subscriptions.sumOf { it.second.size }
        require(totalLinks in 1..MAX_BACKUP_PROFILES) { "Резервная копия не содержит серверов или слишком велика" }
        subscriptions.forEach { (_, _, titles) -> SubscriptionMetadataCodec.decode(titles.first) }

        val existing = all().toSet()
        val editor = prefs.edit()
        manualLinks.filterNot { it in existing }.forEach { link ->
            editor.putString(PROFILE_PREFIX + java.util.UUID.randomUUID().toString(), encrypt(link))
        }
        subscriptions.forEach { (source, links, extra) ->
            val id = sourceId(source)
            val profilePrefix = SUBSCRIPTION_PROFILE_PREFIX + id + "_"
            prefs.all.keys.filter { it.startsWith(profilePrefix) }.forEach(editor::remove)
            links.forEachIndexed { index, link ->
                editor.putString(SubscriptionProfileKey.storageKey(id, index, link), encrypt(link))
            }
            editor.putString(SUBSCRIPTION_SOURCE_PREFIX + id, encrypt(source))
            editor.putString(SUBSCRIPTION_META_PREFIX + id, extra.first)
            editor.remove(SUBSCRIPTION_TITLE_PREFIX + id)
            if (extra.second != null) editor.putString(SUBSCRIPTION_CUSTOM_TITLE_PREFIX + id, extra.second)
            else editor.remove(SUBSCRIPTION_CUSTOM_TITLE_PREFIX + id)
            if (extra.third != null) editor.putString(SUBSCRIPTION_PROVIDER_TITLE_PREFIX + id, extra.third)
            else editor.remove(SUBSCRIPTION_PROVIDER_TITLE_PREFIX + id)
        }
        check(editor.commit()) { "Не удалось восстановить резервную копию" }
        profilesCache = null
        summariesCache = null
        totalLinks
    }

    private fun isSupportedBackupLink(link: String): Boolean {
        val lower = link.lowercase(java.util.Locale.ROOT)
        return link.length in 1..16_384 && (
            lower.startsWith("vless://") ||
                lower.startsWith("hysteria2://") ||
                lower.startsWith("hy2://") ||
                lower.startsWith("tuic://")
            )
    }

    private fun sourceId(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun encrypt(value: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        Base64.encodeToString(packed, Base64.NO_WRAP)
    } catch (error: InvalidKeyException) {
        // Encryption can fail too when Android invalidates a Keystore key.
        // Keep the same actionable recovery message used by the read path.
        throw ProfileStorageException(storageRecoveryMessage(), error)
    }

    private fun decryptStored(value: String): String = try {
        decrypt(value)
    } catch (error: KeyPermanentlyInvalidatedException) {
        throw ProfileStorageException(storageRecoveryMessage(), error)
    } catch (error: InvalidKeyException) {
        throw ProfileStorageException(storageRecoveryMessage(), error)
    } catch (error: IllegalArgumentException) {
        throw ProfileStorageException(storageCorruptionMessage(), error)
    } catch (error: AEADBadTagException) {
        throw ProfileStorageException(storageCorruptionMessage(), error)
    } catch (error: BufferUnderflowException) {
        throw ProfileStorageException(storageCorruptionMessage(), error)
    }

    private fun storageCorruptionMessage() =
        "Не удалось проверить одну из сохранённых записей Astra: данные повреждены или не подходят к текущему ключу. Записи не удалены. Не очищай данные приложения; проверь резервную копию или повторно импортируй серверы после восстановления."

    private fun storageRecoveryMessage() =
        "Не удалось открыть защищённое хранилище Astra. Зашифрованные записи сохранены без изменений. Не удаляй данные приложения; если восстановление ключа невозможно, серверы потребуется импортировать заново."

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(packed)
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..16 && buffer.remaining() > ivLength)
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        } catch (error: Exception) {
            throw ProfileStorageException(storageRecoveryMessage(), error)
        }
        try {
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        } catch (error: Exception) {
            throw ProfileStorageException(storageRecoveryMessage(), error)
        }
        val hasEncryptedRecords = prefs.all.keys.any {
            it == RECOVERY_PROFILE_KEY ||\n                it == BANK_PAUSE_PROFILE_KEY ||
                it.startsWith(PROFILE_PREFIX) ||
                it.startsWith(SUBSCRIPTION_PROFILE_PREFIX) ||
                it.startsWith(SUBSCRIPTION_SOURCE_PREFIX)
        }
        if (hasEncryptedRecords) throw ProfileStorageException(storageRecoveryMessage())
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val RECOVERY_PROFILE_KEY = "recovery_profile_encrypted"
        private const val BANK_PAUSE_PROFILE_KEY = "bank_pause_profile_encrypted"
        private const val BANK_PAUSE_AT_KEY = "bank_pause_at"
        private const val SELECTED_PROFILE_KEY = "selected_profile_encrypted"
        private const val PROFILE_PREFIX = "profile_"
        private const val SUBSCRIPTION_PROFILE_PREFIX = "subscription_profile_"
        private const val SUBSCRIPTION_SOURCE_PREFIX = "subscription_source_"
        private const val SUBSCRIPTION_META_PREFIX = "subscription_meta_"
        private const val SUBSCRIPTION_TITLE_PREFIX = "subscription_title_" // legacy title key
        private const val SUBSCRIPTION_CUSTOM_TITLE_PREFIX = "subscription_custom_title_"
        private const val SUBSCRIPTION_PROVIDER_TITLE_PREFIX = "subscription_provider_title_"
        private const val MAX_BACKUP_PROFILES = 1000
        private const val MAX_BACKUP_SUBSCRIPTIONS = 100
        private const val KEY_ALIAS = "astra_profile_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val SUBSCRIPTION_MUTATION_LOCK = Any()
    }
}
