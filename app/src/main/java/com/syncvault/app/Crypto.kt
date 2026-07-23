package com.syncvault.app

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based AES-256-GCM encryption.
 */
object Crypto {
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    // Header plaintext format:
    // [MAGIC 4 bytes][NAME_LEN 4 bytes][NAME UTF-8][FILE DATA...]
    private val FORMAT_MAGIC = byteArrayOf(0x53, 0x56, 0x31, 0x00) // "SV1\0"

    fun randomSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /** Derives a 256-bit AES key from [password] and [salt] via PBKDF2. */
    fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS)
        val keyBytes = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts the data from [input] and writes IV + ciphertext to [output].
     * If [originalFileName] is provided, it is stored inside the encrypted payload
     * so the decrypt side can restore the original name.
     */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        key: SecretKey,
        originalFileName: String = ""
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        output.write(iv)

        val safeName = originalFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()

        if (safeName.isNotBlank()) {
            val nameBytes = safeName.toByteArray(Charsets.UTF_8)
            val headerPlain = ByteBuffer
                .allocate(FORMAT_MAGIC.size + Int.SIZE_BYTES + nameBytes.size)
                .put(FORMAT_MAGIC)
                .putInt(nameBytes.size)
                .put(nameBytes)
                .array()

            val encryptedHeader = cipher.update(headerPlain)
            if (encryptedHeader != null && encryptedHeader.isNotEmpty()) {
                output.write(encryptedHeader)
            }
        }

        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            val encryptedChunk = cipher.update(buffer, 0, bytesRead)
            if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                output.write(encryptedChunk)
            }
        }

        val finalChunk = cipher.doFinal()
        if (finalChunk.isNotEmpty()) {
            output.write(finalChunk)
        }
    }

    /** Encrypts a small in-memory blob. Used only for the password-check token. */
    fun encryptBytes(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        return iv + cipher.doFinal(plain)
    }

    /** Decrypts a small in-memory blob produced by [encryptBytes]. */
    fun decryptBytes(data: ByteArray, key: SecretKey): ByteArray {
        val iv = data.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val cipherText = data.copyOfRange(GCM_IV_LENGTH_BYTES, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherText)
    }
}