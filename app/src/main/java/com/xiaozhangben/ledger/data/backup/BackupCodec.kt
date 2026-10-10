package com.xiaozhangben.ledger.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for the backup file.
 *
 * A backup is the full ledger in one file, so it is never written in the clear. The
 * passphrase is stretched with PBKDF2 and the payload sealed with AES-256-GCM, whose
 * authentication tag also means a wrong passphrase and a corrupted file are reported
 * as corruption rather than decrypted into garbage.
 *
 * Layout: `"XZB1" | version(1) | salt(16) | iv(12) | ciphertext+tag`
 */
object BackupCodec {

    private const val MAGIC = "XZB1"
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    /** Must be comfortably long: this key protects the whole ledger. */
    const val MIN_PASSPHRASE_LENGTH = 6

    private val random = SecureRandom()

    class WrongPassphraseException : Exception("口令不正确，或者文件已损坏")

    class NotABackupException : Exception("这不是小账本导出的备份文件")

    fun isBackup(payload: ByteArray): Boolean =
        payload.size > MAGIC.length + 1 + SALT_LENGTH + IV_LENGTH &&
            String(payload, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "口令至少 $MIN_PASSPHRASE_LENGTH 位" }

        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(passphrase, salt), "AES"),
            GCMParameterSpec(TAG_BITS, iv),
        )
        val sealed = cipher.doFinal(plaintext)

        val out = ByteArray(MAGIC.length + 1 + SALT_LENGTH + IV_LENGTH + sealed.size)
        var offset = 0
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, offset); offset += MAGIC.length
        out[offset] = FORMAT_VERSION; offset += 1
        salt.copyInto(out, offset); offset += SALT_LENGTH
        iv.copyInto(out, offset); offset += IV_LENGTH
        sealed.copyInto(out, offset)
        return out
    }

    fun decrypt(payload: ByteArray, passphrase: CharArray): ByteArray {
        if (!isBackup(payload)) throw NotABackupException()

        var offset = MAGIC.length
        @Suppress("UNUSED_VARIABLE")
        val version = payload[offset]; offset += 1
        val salt = payload.copyOfRange(offset, offset + SALT_LENGTH); offset += SALT_LENGTH
        val iv = payload.copyOfRange(offset, offset + IV_LENGTH); offset += IV_LENGTH
        val sealed = payload.copyOfRange(offset, payload.size)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(passphrase, salt), "AES"),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.doFinal(sealed)
        } catch (_: Exception) {
            // The GCM tag failed: either the passphrase is wrong or the bytes changed.
            // Saying which is impossible, and guessing would be misleading.
            throw WrongPassphraseException()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
