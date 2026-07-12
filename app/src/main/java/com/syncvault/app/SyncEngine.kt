package com.syncvault.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class SyncProgress(
    val processed: Int,
    val total: Int,
    val encrypted: Int,
    val skipped: Int,
    val failed: Int
) {
    val progress: Float get() = if (total > 0) processed.toFloat() / total else 1f
}

class SyncEngine(private val context: Context) {

    private val fingerprintsFile = File(context.filesDir, "fingerprints.json")
    private val fingerprints = mutableSetOf<String>()

    init {
        loadFingerprints()
    }

    private fun loadFingerprints() {
        try {
            if (!fingerprintsFile.exists()) {
                fingerprintsFile.writeText("""{"fingerprints":[]}""")
                return
            }
            val content = fingerprintsFile.readText()
            if (content.isBlank()) {
                fingerprintsFile.writeText("""{"fingerprints":[]}""")
                return
            }
            val json = JSONObject(content)
            val arr = json.optJSONArray("fingerprints")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val fp = arr.optString(i, null)
                    if (fp != null) fingerprints.add(fp)
                }
            }
        } catch (e: Exception) {
            // corrupted – reset to empty
            fingerprints.clear()
            fingerprintsFile.writeText("""{"fingerprints":[]}""")
        }
    }

    @Synchronized
    private fun saveFingerprints() {
        try {
            val json = JSONObject().apply {
                put("fingerprints", org.json.JSONArray(fingerprints.toList()))
            }
            // Write to temp then rename to avoid a corrupted fingerprints file
            // if the process dies mid-write.
            val tempFile = File(context.filesDir, "fingerprints.json.tmp")
            tempFile.writeText(json.toString())
            if (!tempFile.renameTo(fingerprintsFile)) {
                // Fallback: direct write if atomic rename isn't available
                fingerprintsFile.writeText(json.toString())
                tempFile.delete()
            }
        } catch (e: Exception) {
            // non-fatal; log could be added if desired
        }
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")
    }

    private fun sha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun sync(
        sourceUri: Uri,
        destUri: Uri,
        onProgress: (SyncProgress) -> Unit,
        onLog: (String) -> Unit,
        onStats: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val sourceDoc = DocumentFile.fromTreeUri(context, sourceUri)
            ?: return@withContext onLog("Error: Cannot access source folder.")
        val destDoc = DocumentFile.fromTreeUri(context, destUri)
            ?: return@withContext onLog("Error: Cannot access destination folder.")

        val files = mutableListOf<DocumentFile>()
        collectFiles(sourceDoc, files)

        val total = files.size
        onLog("Found $total image files.")
        if (total == 0) {
            onStats("No images found.")
            return@withContext
        }

        val cryptoKey = Crypto.getOrCreateKey()
        var processed = 0
        var encrypted = 0
        var skipped = 0
        var failed = 0

        for (file in files) {
            if (!isActive) {
                // FIX: previously this returned without persisting any progress at all.
                // Fingerprints for files already encrypted in this run were saved
                // incrementally below, so cancelling here is now safe: nothing already
                // written to disk will be silently lost or re-processed as a duplicate.
                onLog("Scan cancelled. $encrypted file(s) already encrypted this run remain saved.")
                val summary = "Cancelled. Total: $total, Encrypted: $encrypted, Skipped: $skipped, Failed: $failed"
                onStats(summary)
                return@withContext
            }

            val fileName = file.name ?: continue
            onLog("Processing: $fileName")
            try {
                val hash = context.contentResolver.openInputStream(file.uri)?.use { sha256(it) }
                    ?: throw Exception("Cannot open file for hashing")

                if (fingerprints.contains(hash)) {
                    onLog("Already processed, skipping.")
                    skipped++
                    processed++
                    onProgress(SyncProgress(processed, total, encrypted, skipped, failed))
                    continue
                }

                val finalName = "$hash.enc"

                // FIX: guard against a previous run that encrypted this file but
                // crashed/was killed before its fingerprint got persisted. Without
                // this check the file would be re-encrypted and, since DocumentFile
                // renameTo() does not overwrite, a duplicate "(1)" copy would appear.
                if (destDoc.findFile(finalName) != null) {
                    onLog("Destination file already exists, registering as processed.")
                    fingerprints.add(hash)
                    saveFingerprints()
                    skipped++
                    processed++
                    onProgress(SyncProgress(processed, total, encrypted, skipped, failed))
                    continue
                }

                val tempName = "$hash.enc.tmp"

                // Remove any leftover temp from a previous failed attempt
                destDoc.findFile(tempName)?.delete()

                val tempFile = destDoc.createFile("application/octet-stream", tempName)
                    ?: throw Exception("Cannot create temporary file")

                val inputStream = context.contentResolver.openInputStream(file.uri)
                    ?: throw Exception("Cannot open file")
                val outputStream = context.contentResolver.openOutputStream(tempFile.uri)
                    ?: throw Exception("Cannot open temporary output stream")

                try {
                    Crypto.encryptStream(inputStream, outputStream, cryptoKey)
                    outputStream.flush()
                } finally {
                    inputStream.closeQuietly()
                    outputStream.closeQuietly()
                }

                // Atomic rename
                val renamed = tempFile.renameTo(finalName)
                if (!renamed) {
                    tempFile.delete()
                    throw Exception("Rename to final name failed")
                }

                // FIX: persist the fingerprint immediately after each successful file
                // instead of only once at the very end of the whole scan. This is what
                // makes safe cancellation and crash recovery (checks above) possible.
                fingerprints.add(hash)
                saveFingerprints()
                encrypted++
                onLog("Encrypted and saved as $finalName")
            } catch (e: Exception) {
                onLog("Error: ${e.message}")
                failed++
            }
            processed++
            onProgress(SyncProgress(processed, total, encrypted, skipped, failed))
        }

        val summary = "Scan complete. Total: $total, Encrypted: $encrypted, Skipped: $skipped, Failed: $failed"
        onLog(summary)
        onStats(summary)
    }

    private fun collectFiles(dir: DocumentFile, result: MutableList<DocumentFile>) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                collectFiles(file, result)
            } else if (file.isFile && isImageFile(file.name ?: "")) {
                result.add(file)
            }
        }
    }

    private fun InputStream.closeQuietly() {
        try { close() } catch (_: Exception) {}
    }

    private fun java.io.OutputStream.closeQuietly() {
        try { close() } catch (_: Exception) {}
    }
}
