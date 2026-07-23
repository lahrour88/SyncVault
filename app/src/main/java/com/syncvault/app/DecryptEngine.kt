package com.syncvault.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object DecryptEngine {

    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_LENGTH_BYTES = 32
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BYTES = 16

    private val MAGIC_BYTES = mapOf(
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to ".jpg",
        byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()) to ".png",
        byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x37.toByte(), 0x61.toByte()) to ".gif",
        byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x39.toByte(), 0x61.toByte()) to ".gif",
        byteArrayOf(0x42.toByte(), 0x4D.toByte()) to ".bmp",
    )

    private fun guessExtension(data: ByteArray): String {
        val sample = data.take(12).toByteArray()
        for ((magic, ext) in MAGIC_BYTES) {
            if (sample.size >= magic.size) {
                var match = true
                for (i in magic.indices) {
                    if (sample[i] != magic[i]) {
                        match = false
                        break
                    }
                }
                if (match) return ext
            }
        }
        if (sample.size >= 12 &&
            sample[0] == 0x52.toByte() && sample[1] == 0x49.toByte() &&
            sample[2] == 0x46.toByte() && sample[3] == 0x46.toByte() &&
            sample[8] == 0x57.toByte() && sample[9] == 0x45.toByte() &&
            sample[10] == 0x42.toByte() && sample[11] == 0x50.toByte()
        ) {
            return ".webp"
        }
        return ".bin"
    }

    private fun mimeFromExtension(ext: String): String {
        return when (ext) {
            ".jpg", ".jpeg" -> "image/jpeg"
            ".png" -> "image/png"
            ".gif" -> "image/gif"
            ".bmp" -> "image/bmp"
            ".webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BYTES * 8)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    data class DecryptResult(val success: Int, val failed: Int)

    suspend fun decrypt(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        password: String,
        saltBase64: String,
        onLog: (String) -> Unit,
        onProgress: (current: Int, total: Int) -> Unit
    ): DecryptResult = withContext(Dispatchers.IO) {

        val salt = try {
            android.util.Base64.decode(saltBase64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            onLog("خطأ: قيمة الملح (Salt) غير صالحة (ليست Base64 صحيحة).")
            return@withContext DecryptResult(0, 0)
        }

        val key = deriveKey(password, salt)
        val resolver: ContentResolver = context.contentResolver

        val sourceDoc = DocumentFile.fromTreeUri(context, sourceUri)
        if (sourceDoc == null || !sourceDoc.isDirectory) {
            onLog("خطأ: مجلد المصدر غير صالح.")
            return@withContext DecryptResult(0, 0)
        }

        val encFiles = sourceDoc.listFiles().filter { it.isFile && it.name?.endsWith(".enc") == true }
        if (encFiles.isEmpty()) {
            onLog("لا توجد ملفات بامتداد .enc في المجلد المحدد.")
            return@withContext DecryptResult(0, 0)
        }

        val destDoc = DocumentFile.fromTreeUri(context, destUri)
        if (destDoc == null || !destDoc.isDirectory) {
            onLog("خطأ: مجلد الوجهة غير صالح.")
            return@withContext DecryptResult(0, 0)
        }

        var successCount = 0
        var failCount = 0
        val total = encFiles.size

        encFiles.forEachIndexed { index, file ->
            val fileName = file.name ?: "unknown.enc"
            onProgress(index + 1, total)

            try {
                resolver.openInputStream(file.uri).use { inputStream ->
                    if (inputStream == null) {
                        onLog("[$fileName] فشل: لا يمكن قراءة الملف.")
                        failCount++
                        return@forEachIndexed
                    }

                    // قراءة الملف بالكامل (هذا يستهلك الذاكرة، لكنه بسيط)
                    val encryptedBytes = inputStream.readBytes()
                    if (encryptedBytes.isEmpty()) {
                        onLog("[$fileName] فشل: الملف فارغ.")
                        failCount++
                        return@forEachIndexed
                    }

                    // استخراج IV والـ Tag والنص المشفر
                    val iv = encryptedBytes.sliceArray(0 until IV_LENGTH_BYTES)
                    val ciphertext = encryptedBytes.sliceArray(IV_LENGTH_BYTES until encryptedBytes.size - TAG_LENGTH_BYTES)
                    val tag = encryptedBytes.sliceArray(encryptedBytes.size - TAG_LENGTH_BYTES until encryptedBytes.size)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BYTES * 8, iv))

                    val plaintext = try {
                        cipher.doFinal(ciphertext + tag)
                    } catch (e: javax.crypto.AEADBadTagException) {
                        onLog("[$fileName] فشل: كلمة المرور أو الملح غير صحيحين (AEAD Bad Tag).")
                        failCount++
                        return@forEachIndexed
                    } catch (e: Exception) {
                        onLog("[$fileName] فشل: ${e.message}")
                        failCount++
                        return@forEachIndexed
                    }

                    // تخمين الامتداد من البداية
                    val ext = guessExtension(plaintext)
                    val baseName = fileName.removeSuffix(".enc")
                    val newName = baseName + ext
                    val mimeType = mimeFromExtension(ext)

                    val newFile = destDoc.createFile(mimeType, newName)
                    if (newFile == null) {
                        onLog("[$fileName] فشل: لا يمكن إنشاء الملف في الوجهة.")
                        failCount++
                        return@forEachIndexed
                    }

                    resolver.openOutputStream(newFile.uri).use { outputStream ->
                        outputStream?.write(plaintext)
                    }
                    onLog("[$fileName] -> نجاح: $newName")
                    successCount++
                }
            } catch (e: Exception) {
                onLog("[$fileName] خطأ غير متوقع: ${e.message}")
                failCount++
            }
        }

        onLog("تم الانتهاء. نجاح: $successCount, فشل: $failCount")
        return@withContext DecryptResult(successCount, failCount)
    }
}
