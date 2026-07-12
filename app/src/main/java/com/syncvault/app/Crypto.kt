package com.syncvault.app

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based AES-256-GCM encryption.
 *
 * We deliberately do NOT use Android Keystore for the encryption key: a
 * Keystore key is non-exportable by design and is bound to this specific
 * app install on this specific device. If the app is ever uninstalled or
 * the phone is lost, a Keystore-backed key is gone forever and every
 * encrypted file becomes permanently unreadable.
 *
 * Instead, the key is derived from a password the user chooses, combined
 * with a random salt. The salt is not secret and is written next to the
 * encrypted files (see SyncEngine), so a backup of the DriveSync folder
 * plus the remembered password is enough to reconstruct the key later,
 * independent of this device or this app install.
 */
object Crypto {
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

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
     * Memory usage is constant regardless of input size.
     */
    fun encryptStream(input: InputStream, output: OutputStream, key: SecretKey) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        output.write(iv)

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

    /**
     * Decrypts a small in-memory blob produced by [encryptBytes].
     * Throws (e.g. AEADBadTagException) if [key] is wrong — this is how we
     * detect a mistyped password before touching any real file.
     */
    fun decryptBytes(data: ByteArray, key: SecretKey): ByteArray {
        val iv = data.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val cipherText = data.copyOfRange(GCM_IV_LENGTH_BYTES, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherText)
    }
}
