package com.astra.vpn

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based authenticated encryption for portable profile backups.
 *
 * The caller is responsible for building a backup payload that excludes transient
 * runtime state. No Android Keystore key is used, so a backup can be restored on
 * another device. The passphrase is never stored by this codec.
 */
internal object EncryptedBackupCodec {
    private val MAGIC = "ASTRABK01".toByteArray(StandardCharsets.US_ASCII)
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val KDF_ITERATIONS = 210_000
    private const val MIN_PASSWORD_CHARS = 12
    private const val MAX_PASSWORD_CHARS = 256
    const val MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024
    private const val HEADER_BYTES = 9 + 1 + SALT_BYTES + IV_BYTES
    private const val GCM_TAG_BYTES = TAG_BITS / 8
    private val random = SecureRandom()

    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES) { "Недопустимый размер резервной копии" }
        validatePassword(password)

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        var header: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            header = ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC).put(FORMAT_VERSION).put(salt).put(iv).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            encrypted = cipher.doFinal(plaintext)
            return ByteBuffer.allocate(header.size + encrypted.size)
                .put(header).put(encrypted).array()
        } finally {
            salt.fill(0)
            iv.fill(0)
            header?.fill(0)
            encrypted?.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(backup: ByteArray, password: CharArray): ByteArray {
        require(backup.size in (HEADER_BYTES + GCM_TAG_BYTES)..(HEADER_BYTES + MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES)) {
            "Недопустимый размер файла резервной копии"
        }
        validatePassword(password)

        val input = ByteBuffer.wrap(backup)
        val magic = ByteArray(MAGIC.size).also(input::get)
        var salt: ByteArray? = null
        var iv: ByteArray? = null
        var header: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            require(magic.contentEquals(MAGIC)) { "Это не файл резервной копии Astra" }
            require(input.get() == FORMAT_VERSION) { "Версия резервной копии не поддерживается" }
            salt = ByteArray(SALT_BYTES).also(input::get)
            iv = ByteArray(IV_BYTES).also(input::get)
            header = backup.copyOfRange(0, HEADER_BYTES)
            encrypted = ByteArray(input.remaining()).also(input::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            return try {
                cipher.doFinal(encrypted).also { plaintext ->
                    require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT_BYTES) {
                        "Недопустимый размер содержимого резервной копии"
                    }
                }
            } catch (failure: AEADBadTagException) {
                throw GeneralSecurityException("Неверный пароль или файл повреждён", failure)
            }
        } finally {
            magic.fill(0)
            salt?.fill(0)
            iv?.fill(0)
            header?.fill(0)
            encrypted?.fill(0)
        }
    }

    /** Reads a complete encrypted backup while bounding memory use and wiping scratch buffers. */
    fun readBackup(input: InputStream): ByteArray {
        val maxBytes = HEADER_BYTES + MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
        val output = WipingBackupBuffer()
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(count <= maxBytes - output.size()) { "Файл резервной копии слишком большой" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray().also {
                require(it.isNotEmpty()) { "Файл резервной копии пуст" }
            }
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private class WipingBackupBuffer : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private fun validatePassword(password: CharArray) {
        require(password.size in MIN_PASSWORD_CHARS..MAX_PASSWORD_CHARS) {
            "Пароль резервной копии должен содержать от $MIN_PASSWORD_CHARS до $MAX_PASSWORD_CHARS символов"
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS)
        return try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            try {
                SecretKeySpec(key, "AES")
            } finally {
                key.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }
}
