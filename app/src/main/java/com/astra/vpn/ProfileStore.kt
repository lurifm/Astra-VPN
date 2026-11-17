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

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("astra_profiles", Context.MODE_PRIVATE)

    fun all(): List<String> = prefs.all.entries
        .filter { it.key.startsWith(PROFILE_PREFIX) || it.key.startsWith(SUBSCRIPTION_PROFILE_PREFIX) }
        .sortedBy { it.key }
        .mapNotNull { (_, value) -> decryptStored(value as String) }
        .distinct()

    fun add(link: String) {
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(PROFILE_PREFIX + id, encrypt(link)).apply()
    }

    fun addAll(links: List<String>) {
        val existing = all().toSet()
        val editor = prefs.edit()
        links.distinct().filterNot { it in existing }.forEach { link ->
            editor.putString(PROFILE_PREFIX + java.util.UUID.randomUUID().toString(), encrypt(link))
        }
        editor.apply()
    }

    /** Replace only the profiles owned by this HTTPS subscription. */
    fun replaceSubscription(sourceUrl: String, links: List<String>) {
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
        check(editor.commit()) { "Не удалось сохранить обновлённую подписку" }
    }

    fun subscriptionSources(): List<String> = prefs.all.entries
        .filter { it.key.startsWith(SUBSCRIPTION_SOURCE_PREFIX) }
        .mapNotNull { (_, value) -> decryptStored(value as String) }
        .distinct()

    fun remove(link: String) {
        val entry = prefs.all.entries.firstOrNull { (_, value) ->
            decryptStored(value as String) == link
        } ?: return
        prefs.edit().remove(entry.key).apply()
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

    private fun decryptStored(value: String): String? = try {
        decrypt(value)
    } catch (error: KeyPermanentlyInvalidatedException) {
        throw ProfileStorageException(storageRecoveryMessage(), error)
    } catch (error: InvalidKeyException) {
        throw ProfileStorageException(storageRecoveryMessage(), error)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: AEADBadTagException) {
        null
    } catch (_: BufferUnderflowException) {
        null
    }

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
        private const val KEY_ALIAS = "astra_profile_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
