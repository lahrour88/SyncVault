package com.syncvault.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * محرك المزامنة: يقوم بمسح المجلدات المصدر، حساب الهاش، تشفير الملفات الجديدة/المعدلة،
 * ونسخها إلى مجلد الوجهة (DriveSync). يحتفظ باسم الملف الأصلي داخل البيانات المشفرة.
 */
class SyncEngine(private val context: Context) {

    private val crypto = Crypto(context)

    /**
     * نقطة الدخول الرئيسية لعملية المزامنة.
     * @param sourceUris قائمة بأريطة مجلدات المصدر
     * @param destUri أريط مجلد الوجهة
     * @param password كلمة المرور (للتشفير)
     * @param onProgress دالة استدعاء لتحديث التقدم
     * @param onLog دالة استدعاء لتسجيل الرسائل
     * @param onStats دالة استدعاء لإحصائيات النهاية
     */
    suspend fun sync(
        sourceUris: List<Uri>,
        destUri: Uri,
        password: CharArray,
        onProgress: (SyncProgress) -> Unit,
        onLog: (String) -> Unit,
        onStats: (String) -> Unit
    ) {
        // تنفيذ العملية على خيط خلفي
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val destFolder = DocumentFile.fromTreeUri(context, destUri)
            if (destFolder == null || !destFolder.isDirectory) {
                onLog("خطأ: مجلد الوجهة غير صالح.")
                return@withContext
            }

            // تجميع قائمة الملفات من جميع المجلدات المصدر
            val allFiles = mutableListOf<Pair<Uri, String>>() // (Uri, اسم الملف)
            sourceUris.forEach { srcUri ->
                val folder = DocumentFile.fromTreeUri(context, srcUri)
                if (folder != null && folder.isDirectory) {
                    folder.listFiles().forEach { file ->
                        if (file.isFile) {
                            allFiles.add(file.uri to (file.name ?: "unknown"))
                        }
                    }
                }
            }

            if (allFiles.isEmpty()) {
                onLog("لا توجد ملفات في المجلدات المصدر.")
                return@withContext
            }

            onLog("تم العثور على ${allFiles.size} ملفاً للمعالجة.")

            var processed = 0
            var encrypted = 0
            var skipped = 0
            var failed = 0

            allFiles.forEach { (srcUri, fileName) ->
                try {
                    // إنشاء اسم ملف مشفر (استناداً إلى هاش المحتوى)
                    val contentHash = crypto.hashFile(srcUri)
                    val encFileName = contentHash + ".enc"
                    val destFile = destFolder.findFile(encFileName) ?: destFolder.createFile(
                        "application/octet-stream", encFileName
                    )

                    if (destFile == null) {
                        onLog("خطأ: لا يمكن إنشاء الملف $encFileName في الوجهة.")
                        failed++
                        return@forEach
                    }

                    // التحقق إذا كان الملف موجوداً وتخطيه إذا كان مطابقاً (حسب الحجم أو التاريخ)
                    // (هنا نكتفي بالتحقق من الوجود فقط، يمكن تحسينه)
                    val existingFile = destFolder.findFile(encFileName)
                    if (existingFile != null) {
                        // إذا كان موجوداً، نتحقق من حجمه مقارنة بالمصدر (تخطي بسيط)
                        val srcSize = resolver.openAssetFileDescriptor(srcUri, "r")?.length ?: 0
                        val destSize = resolver.openAssetFileDescriptor(existingFile.uri, "r")?.length ?: 0
                        if (srcSize > 0 && srcSize == destSize) {
                            onLog("تخطي (موجود مسبقاً): $fileName")
                            skipped++
                            return@forEach
                        }
                    }

                    // تشفير الملف مع حفظ اسمه الأصلي
                    val success = encryptFileWithName(
                        inputUri = srcUri,
                        outputUri = destFile.uri,
                        fileName = fileName,
                        password = password
                    )

                    if (success) {
                        encrypted++
                        onLog("تم تشفير: $fileName -> $encFileName")
                    } else {
                        failed++
                        onLog("فشل تشفير: $fileName")
                    }
                } catch (e: Exception) {
                    onLog("خطأ أثناء معالجة $fileName: ${e.message}")
                    failed++
                } finally {
                    processed++
                    onProgress(
                        SyncProgress(
                            progress = processed.toFloat() / allFiles.size,
                            processed = processed,
                            total = allFiles.size,
                            encrypted = encrypted,
                            skipped = skipped,
                            failed = failed
                        )
                    )
                }
            }

            onStats("تم الانتهاء. معالج: $processed, مشفر: $encrypted, مخطي: $skipped, فاشل: $failed")
        }
    }

    /**
     * تشفير ملف مع حفظ اسمه الأصلي (بما في ذلك الامتداد) داخل البيانات المشفرة.
     * التنسيق: [طول الاسم 4 بايت][اسم الملف UTF-8][محتوى الملف]
     */
    private suspend fun encryptFileWithName(
        inputUri: Uri,
        outputUri: Uri,
        fileName: String,
        password: CharArray
    ): Boolean {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null
            try {
                inputStream = resolver.openInputStream(inputUri)
                outputStream = resolver.openOutputStream(outputUri)
                if (inputStream == null || outputStream == null) {
                    return@withContext false
                }

                // قراءة محتوى الملف كاملاً (للملفات الصغيرة والمتوسطة)
                val fileBytes = inputStream.readBytes()

                // تحضير البيانات: 4 بايتات للطول + اسم الملف + المحتوى
                val nameBytes = fileName.toByteArray(Charsets.UTF_8)
                val nameLen = nameBytes.size
                val buffer = java.nio.ByteBuffer.allocate(4 + nameLen + fileBytes.size)
                buffer.putInt(nameLen)          // طول الاسم
                buffer.put(nameBytes)           // الاسم (UTF-8)
                buffer.put(fileBytes)           // المحتوى
                val dataToEncrypt = buffer.array()

                // تشفير البيانات باستخدام Crypto (تقوم بإرجاع IV + ciphertext + Tag)
                val encryptedData = crypto.encrypt(dataToEncrypt, password)

                // كتابة البيانات المشفرة
                outputStream.write(encryptedData)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                inputStream?.close()
                outputStream?.close()
            }
        }
    }
}

/**
 * بيانات تقدم المزامنة.
 */
data class SyncProgress(
    val progress: Float,
    val processed: Int,
    val total: Int,
    val encrypted: Int,
    val skipped: Int,
    val failed: Int
)
