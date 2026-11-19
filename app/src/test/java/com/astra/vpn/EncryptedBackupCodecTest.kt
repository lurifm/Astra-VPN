package com.astra.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class EncryptedBackupCodecTest {
    private val password = "Astra-Backup-2026!".toCharArray()

    @Test
    fun encryptsAndRestoresPayload() {
        val payload = """{"version":1,"profiles":["vless://example"]}""".toByteArray()
        val encrypted = EncryptedBackupCodec.encrypt(payload, password)
        assertArrayEquals(payload, EncryptedBackupCodec.decrypt(encrypted, password))
    }

    @Test
    fun eachEncryptionUsesFreshSaltAndIv() {
        val payload = "same backup".toByteArray()
        val first = EncryptedBackupCodec.encrypt(payload, password)
        val second = EncryptedBackupCodec.encrypt(payload, password)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun rejectsWrongPassword() {
        val encrypted = EncryptedBackupCodec.encrypt("private".toByteArray(), password)
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedBackupCodec.decrypt(encrypted, "Another-Password!".toCharArray())
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val encrypted = EncryptedBackupCodec.encrypt("private".toByteArray(), password)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedBackupCodec.decrypt(encrypted, password)
        }
    }

    @Test
    fun rejectsInvalidHeaderAndTruncatedData() {
        val encrypted = EncryptedBackupCodec.encrypt("private".toByteArray(), password)
        val wrongMagic = encrypted.copyOf().also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.decrypt(wrongMagic, password)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.decrypt(encrypted.copyOf(12), password)
        }
    }

    @Test
    fun rejectsWeakPasswordsAndOversizedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.encrypt(byteArrayOf(1), "short".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.decrypt(byteArrayOf(1), password)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.encrypt(ByteArray(EncryptedBackupCodec.MAX_PLAINTEXT_BYTES + 1), password)
        }
    }
}
