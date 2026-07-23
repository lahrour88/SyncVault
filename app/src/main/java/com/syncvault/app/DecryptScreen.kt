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

Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .verticalScroll(rememberScrollState())
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onNavigateBack) {
            Text("Retour")
        }
        Spacer(modifier = Modifier.weight(1f))
        Text("Déchiffrement", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.weight(1f))
    }

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Mot de passe :") },
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                Text(if (passwordVisible) "Masquer" else "Afficher")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = salt,
        onValueChange = { salt = it },
        label = { Text("Sel (Salt) — format Base64") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = "Saisissez la valeur du sel extraite du fichier syncvault.salt.",
        style = MaterialTheme.typography.bodySmall
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dossier source (.enc) : ", modifier = Modifier.weight(0.4f))
        Text(
            text = displayName(sourceUri?.toString()),
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = { sourcePicker.launch(null) }, modifier = Modifier.weight(0.2f)) {
            Text("Choisir")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dossier de destination : ", modifier = Modifier.weight(0.4f))
        Text(
            text = displayName(destUri?.toString()),
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = { destPicker.launch(null) }, modifier = Modifier.weight(0.2f)) {
            Text("Choisir")
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val canStart = !isRunning && sourceUri != null && destUri != null &&
                password.isNotBlank() && salt.isNotBlank()

        Button(
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
            enabled = canStart
        ) {
            Text(if (isRunning) "En cours..." else "🔓 Déchiffrer")
        }

        if (isRunning) {
            Button(onClick = { /* Annulation possible plus tard */ }) {
                Text("Annuler")
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (progressText.isNotEmpty()) {
        Text(progressText, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
    }

    Text("Journal des opérations :", style = MaterialTheme.typography.titleSmall)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 250.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (logLines.isEmpty()) {
            Text("Aucun journal disponible.", style = MaterialTheme.typography.bodySmall)
        } else {
            logLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

}