package com.syncvault.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val PREFS_NAME = "sync_prefs"
private const val KEY_SOURCE_URIS = "source_uris" // séparés par des retours à la ligne, ordre conservé
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

val canScan = !scanning && sourceUris.isNotEmpty() && destUri != null && password.isNotBlank()

Column(
    modifier = Modifier
        .fillMaxSize()
        .background(SyncVaultColors.BackgroundGray)
        .verticalScroll(rememberScrollState())
        .padding(SyncVaultSpacing.OuterPadding)
) {
    SyncVaultHero(
        title = "SyncVault",
        subtitle = "Protégez et synchronisez vos fichiers en toute sécurité."
    )

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    // Card 1 — Dossiers source
    SyncVaultSectionCard(title = "Dossiers source", icon = Icons.Rounded.Folder) {
        if (sourceUris.isEmpty()) {
            HelperText("Aucun dossier sélectionné.")
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            sourceUris.forEach { uriString ->
                SyncVaultFolderListItem(
                    name = displayName(uriString),
                    onRemove = {
                        sourceUris.remove(uriString)
                        saveSourceUris(prefs, sourceUris)
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        SyncVaultAddFolderButton(
            onClick = { addSourcePicker.launch(null) },
            label = "Ajouter un dossier source"
        )
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    // Card 2 — Dossier DriveSync
    SyncVaultSectionCard(title = "Dossier DriveSync", icon = Icons.Rounded.Cloud) {
        SyncVaultFolderRow(
            label = "Destination sélectionnée",
            currentName = destUri?.let { displayName(it) } ?: "Non défini",
            icon = Icons.Rounded.Cloud,
            onSelect = { destPicker.launch(null) }
        )
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    // Card 3 — Mot de passe de chiffrement
    SyncVaultSectionCard(title = "Mot de passe de chiffrement", icon = Icons.Rounded.Lock) {
        SyncVaultPasswordField(
            value = password,
            onValueChange = { password = it },
            label = "Mot de passe de chiffrement",
            visible = passwordVisible,
            onToggleVisible = { passwordVisible = !passwordVisible }
        )
        Spacer(modifier = Modifier.height(8.dp))
        HelperText(
            "Conservez ce mot de passe. Il n'est jamais enregistré. Le fichier syncvault.salt est stocké dans le dossier DriveSync et sera nécessaire avec ce mot de passe pour déchiffrer les fichiers à l'avenir."
        )
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    // Card 4 — Informations importantes
    SyncVaultSectionCard(title = "Informations importantes", icon = Icons.Rounded.Info) {
        SyncVaultInfoLines(
            lines = listOf(
                "Les fichiers originaux ne sont jamais modifiés.",
                "Seuls les fichiers chiffrés sont synchronisés.",
                "Le chiffrement utilise AES-256-GCM."
            )
        )
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    SyncVaultPrimaryButton(
        text = if (scanning) "Analyse..." else "Analyser les fichiers",
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
                        logLines.add("Erreur grave : ${e.message}")
                    } finally {
                        scanning = false
                        scanJob = null
                    }
                }
            }
        },
        enabled = canScan,
        loading = scanning
    )

    if (scanning) {
        Spacer(modifier = Modifier.height(8.dp))
        SyncVaultSecondaryButton(
            text = "Annuler",
            onClick = { scanJob?.cancel() }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    SyncVaultSecondaryButton(
        text = "Ouvrir le déchiffrement",
        onClick = onNavigateToDecrypt
    )

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    if (scanning || progress.total > 0 || stats.isNotEmpty()) {
        SyncVaultSectionCard(title = "Progression", icon = Icons.Rounded.Cloud) {
            SyncVaultProgressBlock(
                active = scanning,
                progressFraction = if (scanning || progress.total > 0) progress.progress else null,
                statusLine = if (progress.total > 0)
                    "Traités : ${progress.processed}/${progress.total} | Chiffrés : ${progress.encrypted} | Ignorés : ${progress.skipped} | Échecs : ${progress.failed}"
                else ""
            )
            if (stats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stats, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))
    }

    SyncVaultSectionCard(title = "Journal", icon = Icons.Rounded.Info) {
        SyncVaultLogsBlock(logLines = logLines)
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.OuterPadding))
}

}
