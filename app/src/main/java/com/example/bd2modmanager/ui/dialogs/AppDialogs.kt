package com.example.bd2modmanager.ui.dialogs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.data.model.*
import com.example.bd2modmanager.ui.components.InstallJobRow
import com.example.bd2modmanager.ui.components.SelectionRow
import android.net.Uri


@Composable
fun UnpackDialog(
    unpackState: UnpackState,
    inputFile: Uri?,
    onSetInputFile: (Uri) -> Unit,
    onInitiateUnpack: () -> Unit,
    onResetState: () -> Unit,
    onDismiss: () -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { onSetInputFile(it) }
        }
    )

    AlertDialog(
        onDismissRequest = {
            if (unpackState !is UnpackState.Unpacking) {
                onResetState()
                onDismiss()
            }
        },
        icon = { Icon(Icons.Default.Unarchive, contentDescription = "Unpack Bundle") },
        title = { Text("Unpack Bundle") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (val state = unpackState) {
                    is UnpackState.Idle -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SelectionRow(
                                label = "Input File:",
                                value = inputFile?.lastPathSegment ?: "Not selected",
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                            )
                            Text("Output will be saved to Download/outputs/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is UnpackState.Unpacking -> {
                        Text("Unpacking in progress...", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(16.dp))
                        Text(state.progressMessage, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    }
                    is UnpackState.Finished -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Success!", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                    is UnpackState.Failed -> {
                        Icon(Icons.Default.Error, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Operation Failed", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(state.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (unpackState is UnpackState.Idle) {
                Button(
                    onClick = onInitiateUnpack,
                    enabled = inputFile != null
                ) {
                    Text("Unpack")
                }
            }
        },
        dismissButton = {
            if (unpackState !is UnpackState.Unpacking) {
                TextButton(onClick = {
                    onResetState()
                    onDismiss()
                }) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
fun ParallelInstallDialog(
    installJobs: List<InstallJob>,
    finalResult: FinalInstallResult?,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (finalResult != null) onDismiss() },
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .fillMaxWidth(0.95f),
        title = {
            Text(if (finalResult == null) "Processing Mods" else "Batch Repack Complete")
        },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    if (finalResult == null) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(installJobs, key = { it.job.hashedName }) { installJob ->
                                InstallJobRow(installJob)
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            Text("Summary: ${finalResult.successfulJobs} succeeded, ${finalResult.failedJobs} failed.", style = MaterialTheme.typography.titleMedium)
                            
                            // 顯示總耗時
                            if (finalResult.elapsedTimeMs > 0) {
                                val elapsedSeconds = finalResult.elapsedTimeMs / 1000.0
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = String.format("Total time: %.1f seconds", elapsedSeconds),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            finalResult.command?.let {
                                Spacer(Modifier.height(16.dp))
                                Text("Run this command in a root shell to move all files:", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(it))
                                        Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Copy Command")
                                }
                                Spacer(Modifier.height(8.dp))
                                SelectionContainer {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                            .padding(12.dp)
                                            .fillMaxWidth()
                                    )
                                }
                            }
                            
                            // 顯示失敗任務的詳細資訊
                            if (finalResult.failedJobDetails.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Failed Groups:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(8.dp))
                                finalResult.failedJobDetails.forEach { failedJob ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                                            .clickable {
                                                clipboardManager.setText(AnnotatedString(failedJob.error))
                                                Toast.makeText(context, "Error log copied!", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = "Group: ${failedJob.hashedName.take(16)}...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        SelectionContainer {
                                            Text(
                                                text = failedJob.error,
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier
                                                    .horizontalScroll(rememberScrollState())
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (finalResult != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = onDismiss) { Text("OK") }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun UninstallConfirmationDialog(
    targetHash: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (targetHash == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = "Warning") },
        title = { Text("Confirm Restore") },
        text = {
            Text(
                "Are you sure you want to restore the original file for this group?\n\nTarget: ${targetHash.take(12)}...",
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(targetHash) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun UninstallDialog(state: UninstallState, onDismiss: () -> Unit) {
    if (state is UninstallState.Idle) return

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = {
            if (state !is UninstallState.Downloading) {
                onDismiss()
            }
        },
        icon = {
            when (state) {
                is UninstallState.Downloading -> Icon(Icons.Default.Download, contentDescription = "Downloading")
                is UninstallState.Finished -> Icon(Icons.Default.CheckCircle, contentDescription = "Success")
                is UninstallState.Failed -> Icon(Icons.Default.Error, contentDescription = "Failed")
                else -> {}
            }
        },
        title = {
            val text = when (state) {
                is UninstallState.Downloading -> "Restoring Original File..."
                is UninstallState.Finished -> "Restore Successful!"
                is UninstallState.Failed -> "Restore Failed"
                else -> ""
            }
            Text(text)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                when (state) {
                    is UninstallState.Downloading -> {
                        Text("Downloading original file for: ${state.hashedName}", textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.progressMessage, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    is UninstallState.Finished -> {
                        Text("Original file saved to your Downloads folder.", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text("For advanced users, run this command in a root shell to move the file:", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(state.command))
                                Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Command")
                        }
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = state.command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                    is UninstallState.Failed -> Text(state.error, textAlign = TextAlign.Center)
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (state !is UninstallState.Downloading) {
                Button(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
        dismissButton = null
    )
}

@Composable
fun MergeSpineDialog(state: MergeState, onDismiss: () -> Unit) {
    if (state is MergeState.Idle) return

    AlertDialog(
        onDismissRequest = {
            if (state !is MergeState.Merging) {
                onDismiss()
            }
        },
        icon = {
            when (state) {
                is MergeState.Merging -> Icon(Icons.Default.Merge, contentDescription = "Merging")
                is MergeState.Finished -> Icon(Icons.Default.CheckCircle, contentDescription = "Success")
                is MergeState.Failed -> Icon(Icons.Default.Error, contentDescription = "Failed")
                else -> {}
            }
        },
        title = {
            val text = when (state) {
                is MergeState.Merging -> "Merging Spine Assets..."
                is MergeState.Finished -> "Merge Successful!"
                is MergeState.Failed -> "Merge Failed"
                else -> ""
            }
            Text(text)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (state) {
                    is MergeState.Merging -> {
                        Text("Merging spine assets...", textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.progressMessage, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    is MergeState.Finished -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(state.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                    is MergeState.Failed -> {
                        Text(state.error, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (state !is MergeState.Merging) {
                Button(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
        dismissButton = null
    )
}
