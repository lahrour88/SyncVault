package com.syncvault.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.SecretKey

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

    companion object {
        private const val SALT_FILE_NAME = "syncvault.salt"
        private const val CHECK_FILE_NAME = "syncvault.check"
        private const val CHECK_TEXT = "SyncVaultKeyCheck-v1"
    }

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

    /**
     * Loads the salt stored inside [destDoc] (creating it on first run) and
     * derives the AES key from [password]. Then verifies the key against a
     * small encrypted check token stored alongside the salt, so a mistyped
     * password is caught immediately instead of silently producing files
     * that can never be decrypted with the "real" password.
     *
     * Returns the derived key, or null (after logging an error) if the
     * password does not match a pre-existing DriveSync folder.
     *
     * Modified to store salt and check files inside a "metadata" subfolder.
     */
    private fun loadOrCreateKey(destDoc: DocumentFile, password: CharArray, onLog: (String) -> Unit): SecretKey? {
        // Ensure metadata directory exists
        var metadataDir = destDoc.findFile("metadata")
        if (metadataDir == null) {
            metadataDir = destDoc.createDirectory("metadata")
            if (metadataDir == null) {
                onLog("Error: Cannot create metadata directory.")
                return null
            }
        }

        val saltFile = metadataDir.findFile(SALT_FILE_NAME)
        val salt: ByteArray

        if (saltFile == null) {
            // First time encrypting into this folder: generate and store a new salt.
            salt = Crypto.randomSalt()
            // application/octet-stream avoids some SAF providers appending a
            // ".txt" extension onto the name for text/plain.
            val newSaltFile = metadataDir.createFile("application/octet-stream", SALT_FILE_NAME)
                ?: run { onLog("Error: Cannot write $SALT_FILE_NAME to metadata folder."); return null }
            context.contentResolver.openOutputStream(newSaltFile.uri)?.use {
                it.write(Base64.encode(salt, Base64.NO_WRAP))
            } ?: run { onLog("Error: Cannot open $SALT_FILE_NAME for writing."); return null }
        } else {
            val saltText = context.contentResolver.openInputStream(saltFile.uri)?.use { it.readBytes() }
                ?: run { onLog("Error: Cannot read $SALT_FILE_NAME."); return null }
            salt = Base64.decode(saltText, Base64.NO_WRAP)
        }

        val key = Crypto.deriveKey(password, salt)

        val checkFile = metadataDir.findFile(CHECK_FILE_NAME)
        if (checkFile == null) {
            val newCheckFile = metadataDir.createFile("application/octet-stream", CHECK_FILE_NAME)
                ?: run { onLog("Error: Cannot write $CHECK_FILE_NAME to metadata folder."); return null }
            val encrypted = Crypto.encryptBytes(CHECK_TEXT.toByteArray(Charsets.UTF_8), key)
            context.contentResolver.openOutputStream(newCheckFile.uri)?.use { it.write(encrypted) }
                ?: run { onLog("Error: Cannot open $CHECK_FILE_NAME for writing."); return null }
        } else {
            val encrypted = context.contentResolver.openInputStream(checkFile.uri)?.use { it.readBytes() }
                ?: run { onLog("Error: Cannot read $CHECK_FILE_NAME."); return null }
            try {
                val decrypted = Crypto.decryptBytes(encrypted, key)
                if (String(decrypted, Charsets.UTF_8) != CHECK_TEXT) {
                    onLog("Error: Incorrect password for this DriveSync folder.")
                    return null
                }
            } catch (e: GeneralSecurityException) {
                onLog("Error: Incorrect password for this DriveSync folder.")
                return null
            }
        }

        return key
    }

    suspend fun sync(
        sourceUris: List<Uri>,
        destUri: Uri,
        password: CharArray,
        onProgress: (SyncProgress) -> Unit,
        onLog: (String) -> Unit,
        onStats: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (sourceUris.isEmpty()) {
            onLog("Error: No source folders selected.")
            return@withContext
        }

        val destDoc = DocumentFile.fromTreeUri(context, destUri)
            ?: return@withContext onLog("Error: Cannot access destination folder.")

        val cryptoKey = loadOrCreateKey(destDoc, password, onLog) ?: run {
            onStats("Scan aborted: incorrect password.")
            return@withContext
        }

        val files = mutableListOf<DocumentFile>()
        for (sourceUri in sourceUris) {
            val sourceDoc = DocumentFile.fromTreeUri(context, sourceUri)
            if (sourceDoc == null) {
                onLog("Error: Cannot access a source folder, skipping it.")
                continue
            }
            collectFiles(sourceDoc, files)
        }

        val total = files.size
        onLog("Found $total image file(s) across ${sourceUris.size} source folder(s).")
        if (total == 0) {
            onStats("No images found.")
            return@withContext
        }

        var processed = 0
        var encrypted = 0
        var skipped = 0
        var failed = 0

        for (file in files) {
            if (!isActive) {
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

                val renamed = tempFile.renameTo(finalName)
                if (!renamed) {
                    tempFile.delete()
                    throw Exception("Rename to final name failed")
                }

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
