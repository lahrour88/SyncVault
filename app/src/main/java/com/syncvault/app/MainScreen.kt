package com.syncvault.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    var sourceUri by remember { mutableStateOf(prefs.getString("source_uri", null)) }
    var destUri by remember { mutableStateOf(prefs.getString("dest_uri", null)) }
    var scanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf(SyncProgress(0, 0, 0, 0, 0)) }
    val logLines = remember { mutableStateListOf<String>() }
    var stats by remember { mutableStateOf("") }

    // FIX: use applicationContext for the long-lived engine instance so it
    // never holds a reference to the Activity (avoids a potential leak).
    val engine = remember { SyncEngine(context.applicationContext) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            sourceUri = it.toString()
            prefs.edit().putString("source_uri", sourceUri).apply()
        }
    }

    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destUri = it.toString()
            prefs.edit().putString("dest_uri", destUri).apply()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Source: ", modifier = Modifier.weight(0.3f))
            Text(
                text = sourceUri?.let { Uri.parse(it).lastPathSegment ?: it } ?: "Not selected",
                modifier = Modifier.weight(0.4f)
            )
            Button(onClick = { sourcePicker.launch(null) }, modifier = Modifier.weight(0.3f)) {
                Text("Select")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DriveSync: ", modifier = Modifier.weight(0.3f))
            Text(
                text = destUri?.let { Uri.parse(it).lastPathSegment ?: it } ?: "Not selected",
                modifier = Modifier.weight(0.4f)
            )
            Button(onClick = { destPicker.launch(null) }, modifier = Modifier.weight(0.3f)) {
                Text("Select")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!scanning && sourceUri != null && destUri != null) {
                        scanning = true
                        progress = SyncProgress(0, 0, 0, 0, 0)
                        logLines.clear()
                        stats = ""
                        scanJob = scope.launch {
                            try {
                                engine.sync(
                                    sourceUri = Uri.parse(sourceUri!!),
                                    destUri = Uri.parse(destUri!!),
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
                                logLines.add("Fatal error: ${e.message}")
                            } finally {
                                scanning = false
                                scanJob = null
                            }
                        }
                    }
                },
                enabled = !scanning && sourceUri != null && destUri != null
            ) {
                Text("Scan")
            }

            if (scanning) {
                Button(onClick = { scanJob?.cancel() }) {
                    Text("Cancel")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (scanning || progress.total > 0) {
            LinearProgressIndicator(progress = progress.progress, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Processed: ${progress.processed}/${progress.total} | Encrypted: ${progress.encrypted} | Skipped: ${progress.skipped} | Failed: ${progress.failed}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (stats.isNotEmpty()) {
            Text(stats, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text("Log:", style = MaterialTheme.typography.titleSmall)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            logLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
