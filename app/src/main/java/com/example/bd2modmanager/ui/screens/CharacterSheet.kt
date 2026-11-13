package com.example.bd2modmanager.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.data.model.ModCategory
import com.example.bd2modmanager.data.model.ModInfo
import com.example.bd2modmanager.data.model.ResolutionState
import com.example.bd2modmanager.data.model.categoryOf
import com.example.bd2modmanager.ui.components.CharacterAvatar
import com.example.bd2modmanager.ui.components.SkinThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterSheet(
    characterName: String,
    mods: List<ModInfo>,
    selectedMods: Set<Uri>,
    onToggleModSelection: (Uri) -> Unit,
    onSelectAllForCharacter: (Boolean) -> Unit,
    onPreview: (ModInfo) -> Unit,
    onRepackSingle: (ModInfo) -> Unit,
    onRepackBatch: (List<ModInfo>) -> Unit,
    onUninstallBundle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember(characterName, mods) {
        mods.filter {
            it.character.trim() == characterName && it.resolutionState == ResolutionState.KNOWN
        }.sortedWith(compareBy({ categoryOf(it.type, it.costume).ordinal }, { it.costume.lowercase() }, { it.name.lowercase() }))
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        val chosen = remember(entries, selectedMods) {
            entries.filter { it.uri in selectedMods }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CharacterAvatar(characterName = characterName, size = 44.dp, cornerRadius = 10.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    characterName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (entries.isNotEmpty()) {
                    Text(
                        "${entries.size} mod(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (chosen.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${chosen.size} selected",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onSelectAllForCharacter(false) }) { Text("Clear") }
                        Button(onClick = {
                            onRepackBatch(chosen)
                            onDismiss()
                        }) {
                            Text("Repack ${chosen.size}")
                        }
                    }
                }
            } else if (entries.size > 1) {
                TextButton(
                    onClick = { onSelectAllForCharacter(true) },
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    Text("Select All (${entries.size})", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (entries.isEmpty()) {
                Text(
                    "No mods found for this character.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 32.dp)
                )
            } else {
                ModCategory.entries.forEach { cat ->
                    val inCat = entries.filter { categoryOf(it.type, it.costume) == cat }
                    if (inCat.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                cat.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 10.dp).weight(1f))
                        }

                        inCat.forEach { mod ->
                            CharacterSkinRow(
                                mod = mod,
                                checked = mod.uri in selectedMods,
                                onCheckedChange = { onToggleModSelection(mod.uri) },
                                onPreview = { onPreview(mod) },
                                onRepack = {
                                    onRepackSingle(mod)
                                    onDismiss()
                                },
                                onUninstall = {
                                    mod.targetHash?.let { hash ->
                                        onUninstallBundle(hash)
                                        onDismiss()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterSkinRow(
    mod: ModInfo,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onRepack: () -> Unit,
    onUninstall: () -> Unit
) {
    val fileId = mod.resolvedFamilyKey
    val title = mod.costume.trim().ifBlank { mod.name }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)

        SkinThumbnail(fileId = fileId, size = 44.dp, contentDescription = title)

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                mod.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!mod.targetHash.isNullOrBlank()) {
                Text(
                    mod.targetHash,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onPreview) {
                Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = "Preview",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (!mod.targetHash.isNullOrBlank()) {
                IconButton(onClick = onUninstall) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Restore Original",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Button(
                onClick = onRepack,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Repack", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
