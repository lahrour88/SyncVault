package com.syncvault.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ElevatedCardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/* =========================================================================
 * SyncVaultStyle.kt
 *
 * PURE VISUAL LAYER — the "style.css" for SyncVault's Compose "HTML".
 *
 * This file owns ONLY presentation: colors, shapes, spacing, typography
 * helpers and reusable @Composable building blocks. It never owns state,
 * never launches coroutines, never touches ActivityResult launchers and
 * never contains encryption / decryption / sync business logic. All data
 * shown here and all callbacks invoked here are passed in by the existing
 * screen files (MainScreen.kt, DecryptScreen.kt), which remain the single
 * source of truth for state and behavior.
 * ========================================================================= */

// -----------------------------------------------------------------------
// Palette
// -----------------------------------------------------------------------
object SyncVaultColors {
    val Purple = Color(0xFF6E5AD6)
    val PurpleDark = Color(0xFF5541C4)
    val Lavender = Color(0xFFE9E4FB)
    val LavenderSoft = Color(0xFFF4F1FC)
    val BackgroundGray = Color(0xFFF7F7FA)
    val CardWhite = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF211B3D)
    val TextSecondary = Color(0xFF6B6580)
    val Success = Color(0xFF2E7D5B)
    val Warning = Color(0xFFB5892B)
}

// -----------------------------------------------------------------------
// Shapes & spacing
// -----------------------------------------------------------------------
object SyncVaultShapes {
    val Card = RoundedCornerShape(18.dp)
    val Button = RoundedCornerShape(14.dp)
    val Field = RoundedCornerShape(14.dp)
    val Chip = RoundedCornerShape(50)
}

object SyncVaultSpacing {
    val OuterPadding = 16.dp
    val SectionGap = 20.dp
    val InsideCard = 12.dp
}

// -----------------------------------------------------------------------
// Typography helpers
// -----------------------------------------------------------------------
@Composable
fun HeroTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = SyncVaultColors.TextPrimary
    )
}

@Composable
fun HeroSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = SyncVaultColors.TextSecondary
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = SyncVaultColors.TextPrimary
    )
}

@Composable
fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SyncVaultColors.TextSecondary
    )
}

// -----------------------------------------------------------------------
// Hero section
// -----------------------------------------------------------------------
@Composable
fun SyncVaultHero(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Rounded.Shield
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SyncVaultShapes.Card,
        color = SyncVaultColors.Lavender
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SyncVaultSpacing.SectionGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SyncVaultColors.CardWhite),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SyncVaultColors.Purple,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            HeroTitle(title)
            Spacer(modifier = Modifier.height(4.dp))
            HeroSubtitle(subtitle)
        }
    }
}

// -----------------------------------------------------------------------
// Section card wrapper (ElevatedCard equivalent to a <section>)
// -----------------------------------------------------------------------
@Composable
fun SyncVaultSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = SyncVaultShapes.Card,
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = SyncVaultColors.CardWhite
        ),
        elevation = ElevatedCardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(SyncVaultSpacing.SectionGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SyncVaultColors.Purple,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SectionTitle(title)
            }
            Spacer(modifier = Modifier.height(SyncVaultSpacing.InsideCard))
            content()
        }
    }
}

// Needed so the `content` lambda type above resolves without an extra import
// footprint in call sites; re-exported alias.
typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

// -----------------------------------------------------------------------
// Styled password field
// -----------------------------------------------------------------------
@Composable
fun SyncVaultPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        leadingIcon = {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = SyncVaultColors.Purple)
        },
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = null
                )
            }
        },
        shape = SyncVaultShapes.Field,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SyncVaultColors.Purple,
            cursorColor = SyncVaultColors.Purple
        ),
        modifier = modifier.fillMaxWidth()
    )
}

// -----------------------------------------------------------------------
// Styled generic text field (e.g. salt)
// -----------------------------------------------------------------------
@Composable
fun SyncVaultTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = Icons.Rounded.Key
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, tint = SyncVaultColors.Purple) }
        },
        shape = SyncVaultShapes.Field,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SyncVaultColors.Purple,
            cursorColor = SyncVaultColors.Purple
        ),
        modifier = modifier.fillMaxWidth()
    )
}

// -----------------------------------------------------------------------
// Folder row: label + current selection + "Choisir" button
// -----------------------------------------------------------------------
@Composable
fun SyncVaultFolderRow(
    label: String,
    currentName: String,
    icon: ImageVector,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String = "Choisir"
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = SyncVaultColors.Purple, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            HelperText(label)
            Text(
                text = currentName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = SyncVaultColors.TextPrimary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onSelect,
            shape = SyncVaultShapes.Button
        ) {
            Text(buttonLabel)
        }
    }
}

// -----------------------------------------------------------------------
// Source folder list: existing entries + remove + add
// -----------------------------------------------------------------------
@Composable
fun SyncVaultFolderListItem(
    name: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, tint = SyncVaultColors.Purple, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        FilledIconButton(
            onClick = onRemove,
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = SyncVaultColors.LavenderSoft,
                contentColor = SyncVaultColors.PurpleDark
            ),
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Supprimer", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SyncVaultAddFolderButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = SyncVaultShapes.Button,
        modifier = modifier.fillMaxWidth()
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
}

// -----------------------------------------------------------------------
// Information card content (bullet lines)
// -----------------------------------------------------------------------
@Composable
fun SyncVaultInfoLines(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        lines.forEach { line ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("•  ", color = SyncVaultColors.Purple)
                Text(line, style = MaterialTheme.typography.bodySmall, color = SyncVaultColors.TextSecondary)
            }
        }
    }
}

// -----------------------------------------------------------------------
// Buttons
// -----------------------------------------------------------------------
@Composable
fun SyncVaultPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = SyncVaultShapes.Button,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = SyncVaultColors.Purple,
            contentColor = Color.White,
            disabledContainerColor = SyncVaultColors.Lavender,
            disabledContentColor = SyncVaultColors.TextSecondary
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SyncVaultSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = SyncVaultShapes.Button,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Text(text)
    }
}

@Composable
fun SyncVaultDestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = SyncVaultColors.LavenderSoft,
            contentColor = SyncVaultColors.PurpleDark
        ),
        shape = SyncVaultShapes.Button,
        modifier = modifier
    ) {
        Text(text)
    }
}

// -----------------------------------------------------------------------
// Progress section
// -----------------------------------------------------------------------
@Composable
fun SyncVaultProgressBlock(
    active: Boolean,
    progressFraction: Float?,
    statusLine: String,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = active || statusLine.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = progressFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = SyncVaultColors.Purple,
                    trackColor = SyncVaultColors.Lavender
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else if (active) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = SyncVaultColors.Purple,
                    trackColor = SyncVaultColors.Lavender
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (statusLine.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (active) Icons.Rounded.Info else Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = if (active) SyncVaultColors.Purple else SyncVaultColors.Success,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(statusLine, style = MaterialTheme.typography.bodyMedium, color = SyncVaultColors.TextPrimary)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------
// Logs card content
// -----------------------------------------------------------------------
@Composable
fun SyncVaultLogsBlock(
    logLines: List<String>,
    emptyText: String = "Aucun journal disponible.",
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Article, contentDescription = null, tint = SyncVaultColors.Purple, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            SectionTitle("Journal des opérations")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Divider(color = SyncVaultColors.Lavender)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = SyncVaultColors.BackgroundGray,
            shape = SyncVaultShapes.Field,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(SyncVaultSpacing.InsideCard)
            ) {
                if (logLines.isEmpty()) {
                    HelperText(emptyText)
                } else {
                    logLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = SyncVaultColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------
// Simple top bar with back navigation (used by DecryptScreen hero row)
// -----------------------------------------------------------------------
@Composable
fun SyncVaultBackRow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.TextButton(onClick = onBack) {
            Text("← Retour", color = SyncVaultColors.Purple)
        }
    }
}
