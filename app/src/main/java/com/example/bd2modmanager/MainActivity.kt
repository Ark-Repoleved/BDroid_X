package com.example.bd2modmanager

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.ui.theme.BD2ModManagerTheme
import com.example.bd2modmanager.ui.viewmodel.InstallState
import com.example.bd2modmanager.ui.viewmodel.MainViewModel
import com.example.bd2modmanager.ui.viewmodel.ModInfo
import com.example.bd2modmanager.ui.viewmodel.UninstallState
import com.example.bd2modmanager.utils.SafManager
import com.valentinilk.shimmer.shimmer

import androidx.compose.ui.state.ToggleableState

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize(this)

        setContent {
            BD2ModManagerTheme {
                var uninstallConfirmationTarget by remember { mutableStateOf<String?>(null) }

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
                        onSelectModSource = { modSourceDirLauncher.launch(Unit) },
                        onUninstallRequest = { hash -> uninstallConfirmationTarget = hash }
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

                UninstallConfirmationDialog(
                    targetHash = uninstallConfirmationTarget,
                    onConfirm = { hash ->
                        viewModel.initiateUninstall(this, hash)
                        uninstallConfirmationTarget = null
                    },
                    onDismiss = {
                        uninstallConfirmationTarget = null
                    }
                )
            }
        }
    }
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
        onDismissRequest = onDismiss,
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
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

                        SelectionContainer {
                            Text(
                                text = state.command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                                    .fillMaxWidth()
                            )
                        }
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
                    }
                    is UninstallState.Failed -> Text(state.error, textAlign = TextAlign.Center)
                    else -> {}
                }
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
        icon = {
            when (state) {
                is InstallState.AwaitingOriginalFile -> Icon(Icons.Default.Help, contentDescription = "Awaiting File")
                is InstallState.Finished -> Icon(Icons.Default.CheckCircle, contentDescription = "Success")
                is InstallState.Failed -> Icon(Icons.Default.Error, contentDescription = "Failed")
                is InstallState.Installing -> Icon(Icons.Default.Build, contentDescription = "Installing")
                else -> {}
            }
        },
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is InstallState.AwaitingOriginalFile -> {
                        Text("To repack mods for group:", textAlign = TextAlign.Center)
                        Text(state.job.hashedName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        Text("Please provide the original __data file.", textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download from Server")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onProvideFile) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Select File Manually")
                        }
                    }
                    is InstallState.Finished -> {
                        val hash = state.job.hashedName
                        val command = "mv -f /sdcard/Download/__${hash} /sdcard/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/$hash/*/__data"

                        Text("New file saved to Downloads folder as __${hash}.", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text("For advanced users, run this command in a root shell to move the file:", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))

                        SelectionContainer {
                            Text(
                                text = command,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                                    .fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(command))
                                Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Command")
                        }
                    }
                    is InstallState.Failed -> Text(state.error, textAlign = TextAlign.Center)
                    is InstallState.Installing -> {
                        Text("Processing group: ${state.job.hashedName}", textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.progressMessage, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (state) {
                is InstallState.AwaitingOriginalFile -> {
                }
                is InstallState.Finished, is InstallState.Failed -> Button(onClick = onDismiss) { Text("OK") }
                else -> {}
            }
        },
        dismissButton = {
             if (state is InstallState.AwaitingOriginalFile) {
                 TextButton(onClick = onDismiss) { Text("Cancel") }
             }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModScreen(
    viewModel: MainViewModel,
    onSelectModSource: () -> Unit,
    onUninstallRequest: (String) -> Unit
) {
    val modSourceDirectoryUri by viewModel.modSourceDirectoryUri.collectAsState()
    val modsList by viewModel.modsList.collectAsState()
    val groupedMods = modsList.groupBy { it.targetHashedName ?: "Unknown" }
    val selectedMods by viewModel.selectedMods.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            modSourceDirectoryUri?.let { viewModel.scanModSourceDirectory(context, it) }
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            pullToRefreshState.endRefresh()
        }
    }

    Scaffold(
        floatingActionButton = {
            AnimatedVisibility(visible = selectedMods.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.initiateBatchRepack() },
                    icon = { Icon(Icons.Default.Done, contentDescription = "Repack") },
                    text = { Text("Repack Selected") }
                )
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
                WelcomeScreen(onSelectModSource)
            } else {
                Box(modifier = Modifier.nestedScroll(pullToRefreshState.nestedScrollConnection)) {
                    if (isLoading && modsList.isEmpty()) { // Show shimmer only on initial load
                        ShimmerLoadingScreen()
                    } else if (modsList.isEmpty()) {
                        EmptyModsScreen()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            groupedMods.toSortedMap().forEach { (hash, modsInGroup) ->
                                stickyHeader {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "Target: ${hash.take(12)}...",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.weight(1f)
                                            )

                                            IconButton(onClick = { onUninstallRequest(hash) }) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Uninstall",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            val modsInGroupUris = modsInGroup.map { it.uri }.toSet()
                                            val selectedInGroup = selectedMods.intersect(modsInGroupUris)

                                            val checkboxState = when {
                                                selectedInGroup.isEmpty() -> ToggleableState.Off
                                                selectedInGroup.size == modsInGroupUris.size -> ToggleableState.On
                                                else -> ToggleableState.Indeterminate
                                            }

                                            TriStateCheckbox(
                                                state = checkboxState,
                                                onClick = { viewModel.toggleSelectAllForGroup(hash) }
                                            )
                                        }
                                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                                items(
                                    items = modsInGroup,
                                    key = { mod -> mod.uri.toString() }
                                ) { modInfo ->
                                    ModCard(
                                        modInfo = modInfo,
                                        isSelected = modInfo.uri in selectedMods,
                                        onToggleSelection = { viewModel.toggleModSelection(modInfo.uri) }
                                    )
                                }
                            }
                        }
                    }

                    PullToRefreshContainer(
                        modifier = Modifier.align(Alignment.TopCenter),
                        state = pullToRefreshState,
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(onSelectFolder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Welcome!", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "To get started, please select the folder where you store your Brown Dust 2 mods.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onSelectFolder) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Select Mod Source Folder")
        }
    }
}

@Composable
fun EmptyModsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("No Mods Found", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "We couldn't find any valid mods in the selected folder. Make sure your mods are in .zip format or unzipped folders.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
fun ModCard(modInfo: ModInfo, isSelected: Boolean, onToggleSelection: () -> Unit) {
    val elevation by animateDpAsState(if (isSelected) 4.dp else 1.dp, label = "elevation")
    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(CardDefaults.shape)
            .clickable(onClick = onToggleSelection)
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelection() })
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modInfo.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "${modInfo.character} - ${modInfo.costume}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = { /* No action */ },
                label = { Text(modInfo.type.uppercase(), style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    val icon = when(modInfo.type.lowercase()) {
                        "idle" -> Icons.Default.Person
                        "cutscene" -> Icons.Default.Movie
                        else -> Icons.Default.Category
                    }
                    Icon(icon, contentDescription = modInfo.type, Modifier.size(14.dp))
                },
                modifier = Modifier.heightIn(max = 24.dp)
            )
        }
    }
}

@Composable
fun ShimmerLoadingScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(10) {
            ShimmerModCard()
        }
    }
}

@Composable
fun ShimmerModCard() {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shimmer()
    ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .width(100.dp)
                            .height(32.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    )
                }
            }
        }
    }
