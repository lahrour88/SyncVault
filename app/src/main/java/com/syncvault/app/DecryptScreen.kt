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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private fun displayName(uriString: String?): String {
if (uriString == null) return "Non défini"
return Uri.parse(uriString).lastPathSegment ?: uriString
}

@Composable
fun DecryptScreen(
onNavigateBack: () -> Unit
) {
val context = LocalContext.current
val scope = rememberCoroutineScope()

var sourceUri by remember { mutableStateOf<Uri?>(null) }
var destUri by remember { mutableStateOf<Uri?>(null) }

var password by remember { mutableStateOf("") }
var salt by remember { mutableStateOf("") }
var passwordVisible by remember { mutableStateOf(false) }

var isRunning by remember { mutableStateOf(false) }
var progressText by remember { mutableStateOf("") }
val logLines = remember { mutableStateListOf<String>() }

val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    uri?.let {
        context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        sourceUri = it
    }
}

val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    uri?.let {
        context.contentResolver.takePersistableUriPermission(
            it,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        destUri = it
    }
}

val canStart = !isRunning && sourceUri != null && destUri != null &&
        password.isNotBlank() && salt.isNotBlank()

Column(
    modifier = Modifier
        .fillMaxSize()
        .background(SyncVaultColors.BackgroundGray)
        .verticalScroll(rememberScrollState())
        .padding(SyncVaultSpacing.OuterPadding)
) {
    SyncVaultBackRow(onBack = onNavigateBack)

    Spacer(modifier = Modifier.height(8.dp))

    SyncVaultHero(
        title = "Déchiffrement",
        subtitle = "Restaurez vos fichiers en toute sécurité."
    )

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    // Card 1 — Mot de passe
    SyncVaultSectionCard(title = "Mot de passe", icon = Icons.Rounded.Lock) {
        SyncVaultPasswordField(
            value = password,
            onValueChange = { password = it },
            label = "Mot de passe",
            visible = passwordVisible,
            onToggleVisible = { passwordVisible = !passwordVisible }
        )
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    // Card 2 — Sel cryptographique
    SyncVaultSectionCard(title = "Sel cryptographique", icon = Icons.Rounded.Key) {
        SyncVaultTextField(
            value = salt,
            onValueChange = { salt = it },
            label = "Sel (Salt) — format Base64",
            leadingIcon = Icons.Rounded.Key
        )
        Spacer(modifier = Modifier.height(8.dp))
        HelperText("Saisissez la valeur du sel extraite du fichier syncvault.salt.")
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    // Card 3 — Dossier contenant les fichiers chiffrés
    SyncVaultSectionCard(title = "Dossier contenant les fichiers chiffrés", icon = Icons.Rounded.Folder) {
        SyncVaultFolderRow(
            label = "Dossier source (.enc)",
            currentName = displayName(sourceUri?.toString()),
            icon = Icons.Rounded.Folder,
            onSelect = { sourcePicker.launch(null) }
        )
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    // Card 4 — Dossier de destination
    SyncVaultSectionCard(title = "Dossier de destination", icon = Icons.Rounded.FolderOpen) {
        SyncVaultFolderRow(
            label = "Dossier de destination",
            currentName = displayName(destUri?.toString()),
            icon = Icons.Rounded.FolderOpen,
            onSelect = { destPicker.launch(null) }
        )
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    SyncVaultPrimaryButton(
        text = if (isRunning) "En cours..." else "Déchiffrer",
        onClick = {
            if (canStart) {
                isRunning = true
                logLines.clear()
                progressText = "Traitement en cours..."

                scope.launch {
                    try {
                        val result = DecryptEngine.decrypt(
                            context = context,
                            sourceUri = sourceUri!!,
                            destUri = destUri!!,
                            password = password,
                            saltBase64 = salt,
                            onLog = { msg ->
                                logLines.add(msg)
                                if (logLines.size > 200) logLines.removeAt(0)
                            },
                            onProgress = { current, total ->
                                progressText = "Traitement : $current / $total"
                            }
                        )
                        progressText = "Déchiffrement terminé : ${result.success} réussi(s), ${result.failed} échec(s)"
                    } catch (e: Exception) {
                        logLines.add("Erreur grave : ${e.message}")
                        progressText = "Échec de l'opération"
                    } finally {
                        isRunning = false
                    }
                }
            }
        },
        enabled = canStart,
        loading = isRunning
    )

    if (isRunning) {
        Spacer(modifier = Modifier.height(8.dp))
        SyncVaultSecondaryButton(
            text = "Annuler",
            onClick = { /* Annulation possible plus tard */ }
        )
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))

    if (progressText.isNotEmpty()) {
        SyncVaultSectionCard(title = "Progression", icon = Icons.Rounded.FolderOpen) {
            SyncVaultProgressBlock(
                active = isRunning,
                progressFraction = null,
                statusLine = progressText
            )
        }
        Spacer(modifier = Modifier.height(SyncVaultSpacing.SectionGap))
    }

    SyncVaultSectionCard(title = "Journal", icon = Icons.Rounded.Folder) {
        SyncVaultLogsBlock(logLines = logLines)
    }

    Spacer(modifier = Modifier.height(SyncVaultSpacing.OuterPadding))
}

}
