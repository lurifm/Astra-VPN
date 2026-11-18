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
        links.distinct().forEach { link ->
            editor.putString(profilePrefix + java.util.UUID.randomUUID().toString(), encrypt(link))
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
        private const val PROFILE_PREFIX = "profile_"
        private const val SUBSCRIPTION_PROFILE_PREFIX = "subscription_profile_"
        private const val SUBSCRIPTION_SOURCE_PREFIX = "subscription_source_"
        private const val SUBSCRIPTION_META_PREFIX = "subscription_meta_"
        private const val SUBSCRIPTION_TITLE_PREFIX = "subscription_title_" // legacy title key
        private const val SUBSCRIPTION_CUSTOM_TITLE_PREFIX = "subscription_custom_title_"
        private const val SUBSCRIPTION_PROVIDER_TITLE_PREFIX = "subscription_provider_title_"
        private const val KEY_ALIAS = "astra_profile_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val SUBSCRIPTION_MUTATION_LOCK = Any()
    }
}
