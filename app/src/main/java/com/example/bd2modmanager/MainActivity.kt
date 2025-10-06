package com.example.bd2modmanager

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
import androidx.compose.material.icons.filled.Done
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
import com.example.bd2modmanager.ui.viewmodel.UninstallState
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
                InstallDialog(
                    state = installState,
                    onDismiss = { viewModel.resetInstallState() },
                    onProvideFile = { originalDataLauncher.launch("*/*") },
                    onDownload = { viewModel.downloadOriginalData(context = this) }
                )

                val uninstallState by viewModel.uninstallState.collectAsState()
                UninstallDialog(
                    state = uninstallState,
                    onDismiss = { viewModel.resetUninstallState() }
                )
            }
        }
    }
}

@Composable
fun UninstallDialog(state: UninstallState, onDismiss: () -> Unit) {
    if (state is UninstallState.Idle) return

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
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
            when (state) {
                is UninstallState.Downloading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Downloading original file for: ${state.hashedName}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(state.progressMessage, textAlign = TextAlign.Center)
                    }
                }
                is UninstallState.Finished -> {
                    Column {
                        Text("Original file saved to your Downloads folder.")
                        Spacer(Modifier.height(16.dp))
                        Text("For advanced users, run the following command in a root shell to move the file:")
                        Spacer(Modifier.height(8.dp))

                        SelectionContainer {
                            Text(
                                text = state.command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                                    .fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(state.command))
                                    Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Copy Command")
                            }
                        }
                    }
                }
                is UninstallState.Failed -> Text(state.error)
                else -> {}
            }
        },
        confirmButton = {
            if (state !is UninstallState.Downloading) {
                Button(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = null
    )
}

@Composable
fun InstallDialog(state: InstallState, onDismiss: () -> Unit, onProvideFile: () -> Unit, onDownload: () -> Unit) {
    if (state is InstallState.Idle) return

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val text = when (state) {
                is InstallState.AwaitingOriginalFile -> "Original File Needed"
                is InstallState.Finished -> "Repack Successful!"
                is InstallState.Failed -> "Installation Failed"
                is InstallState.Installing -> "Processing..."
                else -> ""
            }
            Text(text)
        },
        text = {
            when (state) {
                is InstallState.AwaitingOriginalFile ->
                    Column {
                        Text("To repack mods for group ")
                        SelectionContainer { Text(state.job.hashedName, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        Text("Please provide the original __data file.")
                    }

                is InstallState.Finished -> {
                    val hash = state.job.hashedName
                    val command = "mv -f /sdcard/Download/__${hash} /sdcard/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/$hash/*/__data"

                    Column {
                        Text("New file for group '$hash' saved to your Downloads folder as __${hash}.")
                        Spacer(Modifier.height(16.dp))
                        Text("For advanced users, run the following command in a root shell to move the file:")
                        Spacer(Modifier.height(8.dp))

                        SelectionContainer {
                            Text(
                                text = command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                                    .fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(command))
                                    Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Copy Command")
                            }
                        }
                    }
                }
                is InstallState.Failed -> Text(state.error)
                is InstallState.Installing -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Processing group: ${state.job.hashedName}")
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(state.progressMessage, textAlign = TextAlign.Center)
                    }
                }
                else -> {}
            }
        },
        confirmButton = {
            when (state) {
                is InstallState.AwaitingOriginalFile -> {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onDownload) { Text("Download from Server") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onProvideFile) { Text("Select File Manually") }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
                is InstallState.Finished, is InstallState.Failed -> Button(onClick = onDismiss) { Text("OK") }
                else -> {}
            }
        },
        dismissButton = null
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
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (modSourceDirectoryUri == null) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Welcome! Please select your mods folder.", textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onSelectModSource) { Text("Select Mod Source Folder") }
                }
            } else {
                if (isLoading) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Scanning for mods...")
                    }
                } else if (groupedMods.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No mods found in the selected directory.")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        groupedMods.toSortedMap().forEach { (hash, modsInGroup) ->
                            stickyHeader {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(horizontal = 16.dp, vertical = 0.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Target: ${hash.take(12)}...",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TextButton(
                                        onClick = { viewModel.initiateUninstall(context, hash) }
                                    ) {
                                        Text("UNINSTALL")
                                    }
                                }
                            }
                            items(
                                items = modsInGroup,
                                key = { mod -> mod.uri.toString() } // Use URI as a stable key
                            ) { modInfo ->
                                ModCard(
                                    modInfo = modInfo,
                                    isSelected = modInfo.uri in selectedMods,
                                    onToggleSelection = { viewModel.toggleModSelection(modInfo.uri) }
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
