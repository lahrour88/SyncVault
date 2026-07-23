package com.syncvault.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val PREFS_NAME = "sync_prefs"
private const val KEY_SOURCE_URIS = "source_uris" // newline-separated, order preserved
private const val KEY_DEST_URI = "dest_uri"

private fun loadSourceUris(prefs: android.content.SharedPreferences): List<String> =
    prefs.getString(KEY_SOURCE_URIS, "")
        ?.split("\n")
        ?.filter { it.isNotBlank() }
        ?: emptyList()

private fun saveSourceUris(prefs: android.content.SharedPreferences, uris: List<String>) {
    prefs.edit().putString(KEY_SOURCE_URIS, uris.joinToString("\n")).apply()
}

private fun displayName(uriString: String): String =
    Uri.parse(uriString).lastPathSegment ?: uriString

@Composable
fun MainScreen(
    onNavigateToDecrypt: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val sourceUris = remember { mutableStateListOf<String>().apply { addAll(loadSourceUris(prefs)) } }
    var destUri by remember { mutableStateOf(prefs.getString(KEY_DEST_URI, null)) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf(SyncProgress(0, 0, 0, 0, 0)) }
    val logLines = remember { mutableStateListOf<String>() }
    var stats by remember { mutableStateOf("") }

    // Long-lived engine instance uses applicationContext so it never holds
    // a reference to the Activity (avoids a potential leak).
    val engine = remember { SyncEngine(context.applicationContext) }

    val addSourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val uriString = it.toString()
            if (!sourceUris.contains(uriString)) {
                sourceUris.add(uriString)
                saveSourceUris(prefs, sourceUris)
            }
        }
    }

    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destUri = it.toString()
            prefs.edit().putString(KEY_DEST_URI, destUri).apply()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("المجلدات المصدر:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))

        if (sourceUris.isEmpty()) {
            Text("لم يتم اختيار أي مجلد.", style = MaterialTheme.typography.bodyMedium)
        }
        sourceUris.forEach { uriString ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Text(displayName(uriString), modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    sourceUris.remove(uriString)
                    saveSourceUris(prefs, sourceUris)
                }) {
                    Text("إزالة")
                }
            }
        }
        Button(onClick = { addSourcePicker.launch(null) }) {
            Text("+ إضافة مجلد مصدر")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مجلد DriveSync: ", modifier = Modifier.weight(0.3f))
            Text(
                text = destUri?.let { displayName(it) } ?: "غير محدد",
                modifier = Modifier.weight(0.4f)
            )
            Button(onClick = { destPicker.launch(null) }, modifier = Modifier.weight(0.3f)) {
                Text("اختيار")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("كلمة المرور للتشفير") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) "إخفاء" else "إظهار")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "تذكر كلمة المرور هذه. لا يتم تخزينها مطلقًا. يتم حفظ ملح غير سري داخل مجلد DriveSync — ستحتاج إلى كلمة المرور وهذا المجلد لفك تشفير الملفات في المستقبل. (التطبيق من عبد العظيم)",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        val canScan = !scanning && sourceUris.isNotEmpty() && destUri != null && password.isNotBlank()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (canScan) {
                        scanning = true
                        progress = SyncProgress(0, 0, 0, 0, 0)
                        logLines.clear()
                        stats = ""
                        scanJob = scope.launch {
                            try {
                                engine.sync(
                                    sourceUris = sourceUris.map { Uri.parse(it) },
                                    destUri = Uri.parse(destUri!!),
                                    password = password.toCharArray(),
                                    onProgress = { progress = it },
                                    onLog = { msg ->
                                        logLines.add(msg)
                                        if (logLines.size > 200) {
                                            logLines.removeAt(0)
                                        }
                                    },
                                    onStats = { stats = it }
                                )
                            } catch (e: Exception) {
                                logLines.add("خطأ جسيم: ${e.message}")
                            } finally {
                                scanning = false
                                scanJob = null
                            }
                        }
                    }
                },
                enabled = canScan
            ) {
                Text(if (scanning) "جاري..." else "مسح")
            }

            if (scanning) {
                Button(onClick = { scanJob?.cancel() }) {
                    Text("إلغاء")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ✅ التصحيح هنا: أغلقنا قوس Text بشكل صحيح
        Button(
            onClick = onNavigateToDecrypt,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Text("صفحة فك التشفير")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (scanning || progress.total > 0) {
            LinearProgressIndicator(progress = progress.progress, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "تمت المعالجة: ${progress.processed}/${progress.total} | تم التشفير: ${progress.encrypted} | تم التخطي: ${progress.skipped} | فشل: ${progress.failed}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (stats.isNotEmpty()) {
            Text(stats, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text("السجل:", style = MaterialTheme.typography.titleSmall)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 280.dp)
                .verticalScroll(rememberScrollState())
        ) {
            logLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}