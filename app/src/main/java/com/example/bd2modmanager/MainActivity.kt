package com.example.bd2modmanager

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.ui.viewmodel.InstallState
import com.example.bd2modmanager.ui.viewmodel.MainViewModel
import com.example.bd2modmanager.ui.viewmodel.ModInfo
import com.example.bd2modmanager.utils.SafManager

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize(this)

        setContent {
            BD2ModManagerTheme {
                val modSourceDirLauncher = rememberLauncherForActivityResult(
                    contract = SafManager.PickDirectoryWithSpecialAccess(),
                    onResult = { uri ->
                        if (uri != null) {
                            // The Activity now correctly handles persisting the permission
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            viewModel.setModSourceDirectoryUri(this, uri)
                        }
                    }
                )

                val originalDataLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent(),
                    onResult = { uri -> if (uri != null) viewModel.proceedWithInstall(this, uri) }
                )

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ModScreen(
                        viewModel = viewModel,
                        onSelectModSource = { modSourceDirLauncher.launch(Unit) }
                    )
                }

                val installState by viewModel.installState.collectAsState()
                InstallDialog(state = installState, onDismiss = { viewModel.resetInstallState() }) {
                    originalDataLauncher.launch("*/*")
                }
            }
        }
    }
}

@Composable
fun InstallDialog(state: InstallState, onDismiss: () -> Unit, onProvideFile: () -> Unit) {
    if (state is InstallState.Idle) return

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        // Icon and Title
        icon = {
            when (state) {
                is InstallState.AwaitingOriginalFile -> Icon(Icons.Default.Info, contentDescription = "Info")
                is InstallState.Finished -> Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = MaterialTheme.colorScheme.primary)
                is InstallState.Failed -> Icon(Icons.Default.Info, contentDescription = "Error", tint = MaterialTheme.colorScheme.error) // Use Info icon tinted red
                is InstallState.Installing -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                is InstallState.Idle -> {}
            }
        },
        title = {
            val text = when (state) {
                is InstallState.AwaitingOriginalFile -> "Original File Needed"
                is InstallState.Finished -> "Repack Successful!"
                is InstallState.Failed -> "Installation Failed"
                is InstallState.Installing -> "Installing..."
                is InstallState.Idle -> ""
            }
            Text(text)
        },
        // Content
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is InstallState.AwaitingOriginalFile -> {
                        Text("Please provide the original __data file for group:", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = state.job.hashedName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)).padding(8.dp)
                            )
                        }
                    }
                    is InstallState.Finished -> {
                        val hash = state.job.hashedName
                        val command = "mv -f /sdcard/Download/__$hash /sdcard/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/$hash/*/__data"
                        Text("New file saved to your Downloads folder.", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text("For advanced users, use this command to move the file:", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)).padding(8.dp).fillMaxWidth()
                            )
                        }
                    }
                    is InstallState.Failed -> {
                        SelectionContainer {
                            Text(state.error, textAlign = TextAlign.Center)
                        }
                    }
                    is InstallState.Installing -> {
                        Text("Processing group: ${state.job.hashedName}", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(state.progressMessage, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                    is InstallState.Idle -> {}
                }
            }
        },
        // Buttons
        confirmButton = {
            when (state) {
                is InstallState.AwaitingOriginalFile -> Button(onClick = onProvideFile) { Text("Select File") }
                is InstallState.Finished -> {
                    Button(onClick = {
                        clipboardManager.setText(AnnotatedString("mv -f /sdcard/Download/__${state.job.hashedName} /sdcard/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/${state.job.hashedName}/*/__data"))
                        Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy Command")
                    }
                }
                is InstallState.Failed -> Button(onClick = onDismiss) { Text("OK") }
                else -> {} // No confirm button while installing or idle
            }
        },
        dismissButton = {
            if (state !is InstallState.Installing) {
                Button(onClick = onDismiss) {
                    Text(if (state is InstallState.Finished) "Done" else "Cancel")
                }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModScreen(
    viewModel: MainViewModel,
    onSelectModSource: () -> Unit
) {
    val modSourceDirectoryUri by viewModel.modSourceDirectoryUri.collectAsState()
    val modsList by viewModel.modsList.collectAsState()
    val groupedMods = modsList.groupBy { it.targetHashedName ?: "Unknown" }
    val selectedMods by viewModel.selectedMods.collectAsState()

    Scaffold(
        floatingActionButton = {
            if (selectedMods.isNotEmpty()) {
                FloatingActionButton(onClick = { viewModel.initiateBatchRepack() }) {
                    Icon(Icons.Default.Done, contentDescription = "Repack Selected")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (modSourceDirectoryUri == null) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Welcome! Please select your mods folder.", textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onSelectModSource) { Text("Select Mod Source Folder") }
                }
            } else {
                if (groupedMods.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No mods found in the selected directory.")
                    }
                }
                else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        groupedMods.toSortedMap().forEach { (hash, modsInGroup) ->
                            stickyHeader {
                                Text(
                                    text = "Target: ${hash.take(12)}...",
                                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleSmall, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(modsInGroup) {
                                ModCard(
                                    modInfo = it,
                                    isSelected = it.uri in selectedMods,
                                    onToggleSelection = { viewModel.toggleModSelection(it.uri) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModCard(modInfo: ModInfo, isSelected: Boolean, onToggleSelection: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelection)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = modInfo.name, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = modInfo.type.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "${modInfo.character} - ${modInfo.costume}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Checkbox(checked = isSelected, onCheckedChange = { onToggleSelection() })
    }
}

@Composable
fun BD2ModManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}