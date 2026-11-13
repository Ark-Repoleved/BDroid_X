package com.example.bd2modmanager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.data.model.ModInfo
import com.example.bd2modmanager.data.model.ResolutionState
import com.example.bd2modmanager.data.repository.AvatarRepository
import com.example.bd2modmanager.ui.components.RecoveryBanner
import com.example.bd2modmanager.ui.viewmodel.MainViewModel
import com.example.bd2modmanager.update.UpdateViewModel
import com.valentinilk.shimmer.shimmer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModScreen(
    viewModel: MainViewModel,
    updateViewModel: UpdateViewModel,
    onSelectModSource: () -> Unit,
    onUninstallRequest: (String) -> Unit,
    onUnpackRequest: () -> Unit,
    onMergeRequest: () -> Unit
) {
    val modSourceDirectoryUri by viewModel.modSourceDirectoryUri.collectAsState()
    val modsList by viewModel.filteredModsList.collectAsState()
    val allModsList by viewModel.modsList.collectAsState()
    val groupedMods = modsList.groupBy {
        when (it.resolutionState) {
            ResolutionState.KNOWN -> it.targetHash ?: "Unknown"
            ResolutionState.UNKNOWN -> "Unknown"
            ResolutionState.INVALID -> "Invalid"
        }
    }
    val selectedMods by viewModel.selectedMods.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val showShimmer by viewModel.showShimmer.collectAsState()
    val context = LocalContext.current
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val characterEntries by viewModel.characterEntries.collectAsState()
    val avatarSyncState by viewModel.avatarSyncState.collectAsState()
    val recoveryNotices by viewModel.recoveryNotices.collectAsState()
    val isInstallRunning by viewModel.isInstallServiceRunning.collectAsState()
    val betaChannel by updateViewModel.betaChannel.collectAsState()
    val updateChecking by updateViewModel.updateChecking.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var pickedCharacter by remember { mutableStateOf<String?>(null) }
    val modsByCharacter = remember(allModsList) { allModsList.groupBy { it.character.trim() } }


    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            modSourceDirectoryUri?.let { viewModel.scanModSourceDirectory(it) }
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            pullToRefreshState.endRefresh()
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val selectedQuality by viewModel.selectedQuality.collectAsState()
                var qualityExpanded by remember { mutableStateOf(false) }

                AnimatedVisibility(visible = isInstallRunning) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.reopenInstallDialog() },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        icon = { Icon(Icons.Default.Sync, contentDescription = "Install Running") },
                        text = { Text("Task Running") }
                    )
                }

                AnimatedVisibility(visible = !isInstallRunning && selectedMods.isNotEmpty()) {
                    Box {
                        ExtendedFloatingActionButton(
                            onClick = { qualityExpanded = true },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            icon = { Icon(Icons.Default.HighQuality, contentDescription = "Quality") },
                            text = { Text(selectedQuality) }
                        )
                        DropdownMenu(
                            expanded = qualityExpanded,
                            onDismissRequest = { qualityExpanded = false }
                        ) {
                            listOf("SD", "HD", "FHD").forEach { q ->
                                DropdownMenuItem(
                                    text = { Text(q, fontWeight = if (selectedQuality == q) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        viewModel.setSelectedQuality(q)
                                        qualityExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                val canMergeSelected = remember(selectedMods, allModsList, isInstallRunning) {
                    !isInstallRunning &&
                        selectedMods.size == 1 &&
                        allModsList.find { it.uri == selectedMods.first() }?.isDirectory == true
                }
                AnimatedVisibility(visible = canMergeSelected) {
                    ExtendedFloatingActionButton(
                        onClick = onMergeRequest,
                        icon = { Icon(Icons.Default.Merge, contentDescription = "Merge") },
                        text = { Text("Merge Spine") }
                    )
                }
                AnimatedVisibility(visible = !isInstallRunning && modSourceDirectoryUri != null && selectedMods.isEmpty()) {
                    FloatingActionButton(
                        onClick = onUnpackRequest,
                    ) {
                        Icon(Icons.Default.Unarchive, contentDescription = "Unpack Bundle")
                    }
                }
                AnimatedVisibility(visible = !isInstallRunning && selectedMods.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.initiateBatchRepack(context) },
                        icon = { Icon(Icons.Default.Done, contentDescription = "Repack") },
                        text = { Text("Repack Selected") }
                    )
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
                WelcomeScreen(onSelectModSource)
            } else {
                Box(modifier = Modifier.nestedScroll(pullToRefreshState.nestedScrollConnection)) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
                            ) {
                                Box(
                                    modifier = Modifier.size(width = 48.dp, height = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val allModsCount = allModsList.count {
                                        it.resolutionState == ResolutionState.KNOWN
                                    }
                                    val selectedModsCount = selectedMods.size
                                    val checkboxState = when {
                                        selectedModsCount == 0 -> ToggleableState.Off
                                        selectedModsCount == allModsCount && allModsCount > 0 -> ToggleableState.On
                                        selectedModsCount > 0 -> ToggleableState.Indeterminate
                                        else -> ToggleableState.Off
                                    }
                                    TriStateCheckbox(
                                        state = checkboxState,
                                        onClick = { viewModel.toggleSelectAll() }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))

                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                val transition = updateTransition(isSearchActive, label = "search_transition")
                                val collapsedSearchWidth = 40.dp
                                val collapsedAstcWidth = 60.dp
                                val spacerWidth = 8.dp

                                val astcCardWidth by transition.animateDp(
                                    label = "astc_card_width",
                                    transitionSpec = { tween(350) }
                                ) { active ->
                                    if (active) collapsedAstcWidth else maxWidth - collapsedSearchWidth - spacerWidth
                                }

                                val searchCardWidth by transition.animateDp(
                                    label = "search_card_width",
                                    transitionSpec = { tween(350) }
                                ) { active ->
                                    if (active) maxWidth - collapsedAstcWidth - spacerWidth else collapsedSearchWidth
                                }

                                val searchCornerRadius by transition.animateDp(
                                    label = "search_card_corner_radius",
                                    transitionSpec = { tween(350) }
                                ) { active ->
                                    if (active) 16.dp else 20.dp
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    ElevatedCard(
                                        modifier = Modifier.size(width = astcCardWidth, height = 40.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AnimatedVisibility(
                                                visible = !isSearchActive,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    "Use ASTC Compression",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                            val useAstc by viewModel.useAstc.collectAsState()
                                            Switch(
                                                checked = useAstc,
                                                onCheckedChange = { viewModel.setUseAstc(it) },
                                                modifier = Modifier.scale(0.8f).padding(horizontal = 4.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(spacerWidth))

                                    ElevatedCard(
                                        modifier = Modifier.size(width = searchCardWidth, height = 40.dp),
                                        shape = RoundedCornerShape(searchCornerRadius),
                                        onClick = { if (!isSearchActive) viewModel.setSearchActive(true) },
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            AnimatedVisibility(visible = isSearchActive, modifier = Modifier.weight(1f)) {
                                                BasicTextField(
                                                    value = searchQuery,
                                                    onValueChange = viewModel::onSearchQueryChanged,
                                                    modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                    singleLine = true,
                                                    decorationBox = { innerTextField ->
                                                        if (searchQuery.isEmpty()) {
                                                            Text(
                                                                "Search by name...",
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                        innerTextField()
                                                    }
                                                )
                                            }
                                    IconButton(onClick = { viewModel.setSearchActive(!isSearchActive) }) {
                                                Icon(
                                                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                                    contentDescription = "Toggle Search"
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            var menuExpanded by remember { mutableStateOf(false) }
                            Box {
                                ElevatedCard(
                                    modifier = Modifier.size(40.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    onClick = { menuExpanded = true },
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "More Options"
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (updateChecking) "Checking Updates..." else "Check for Updates") },
                                        leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                                        enabled = !updateChecking,
                                        onClick = {
                                            menuExpanded = false
                                            updateViewModel.checkForUpdates(auto = false)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Beta Update Channel") },
                                        leadingIcon = {
                                            Icon(
                                                if (betaChannel) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            updateViewModel.setBetaChannel(!betaChannel)
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Export Diagnostic Logs") },
                                        leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            viewModel.exportDiagnosticLogs(context)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Change Mod Folder") },
                                        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            onSelectModSource()
                                        }
                                    )
                                }
                            }
                        }

                        RecoveryBanner(
                            notices = recoveryNotices,
                            onRetryMove = { viewModel.retryRecoveryMove(context) },
                            onDismiss = { viewModel.dismissRecoveryNotices(context) }
                        )

                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Characters") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("All Mods (${allModsList.size})") }
                            )
                        }

                        if (showShimmer) {
                            ShimmerLoadingScreen()
                        } else if (selectedTab == 0) {
                            CharacterScreen(
                                entries = characterEntries,
                                modsByCharacter = modsByCharacter,
                                selectedMods = selectedMods,
                                searchQuery = searchQuery,
                                onToggleSelectCharacter = { viewModel.toggleSelectAllForCharacter(it) },
                                onPickCharacter = { pickedCharacter = it },
                                avatarSyncFailed = avatarSyncState == AvatarRepository.SyncState.FAILED,
                                onRetryAvatarSync = { viewModel.retryAvatarSync(context) }
                            )
                        } else if (modsList.isEmpty()) {
                            if (searchQuery.isNotEmpty()) {
                                NoSearchResultsScreen(searchQuery)
                            } else {
                                EmptyModsScreen()
                            }
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
                                                when (hash) {
                                                    "Unknown" -> {
                                                        Text(
                                                            text = "Unknown",
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                    "Invalid" -> {
                                                        Text(
                                                            text = "Invalid",
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                    else -> {
                                                        Row(
                                                            modifier = Modifier.weight(1f),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = "Target: ",
                                                                style = MaterialTheme.typography.titleMedium,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.primary
                                                            )
                                                            Text(
                                                                text = hash,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                style = MaterialTheme.typography.titleSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                        }
                                                    }
                                                }

                                                if (hash != "Unknown" && hash != "Invalid") {
                                                    IconButton(onClick = { onUninstallRequest(hash) }) {
                                                        Icon(
                                                            Icons.Default.Delete,
                                                            contentDescription = "Uninstall",
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
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
                                            onToggleSelection = { viewModel.toggleModSelection(modInfo.uri) },
                                            onLongPress = { viewModel.prepareAndShowPreview(context, modInfo) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    PullToRefreshContainer(
                        modifier = Modifier.align(Alignment.TopCenter),
                        state = pullToRefreshState,
                    )
                }

                pickedCharacter?.let { charName ->
                    CharacterSheet(
                        characterName = charName,
                        mods = allModsList,
                        selectedMods = selectedMods,
                        onToggleModSelection = { viewModel.toggleModSelection(it) },
                        onSelectAllForCharacter = { select -> viewModel.selectAllForCharacter(charName, select) },
                        onPreview = { mod -> viewModel.prepareAndShowPreview(context, mod) },
                        onRepackSingle = { mod -> viewModel.initiateBatchRepackMods(context, listOf(mod)) },
                        onRepackBatch = { mods -> viewModel.initiateBatchRepackMods(context, mods) },
                        onUninstallBundle = onUninstallRequest,
                        onDismiss = { pickedCharacter = null }
                    )
                }
            }
        }
    }
}

@Composable
fun NoSearchResultsScreen(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("No Results", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No mods found starting with \"$query\".",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModCard(modInfo: ModInfo, isSelected: Boolean, onToggleSelection: () -> Unit, onLongPress: () -> Unit) {
    val isSelectable = modInfo.resolutionState == ResolutionState.KNOWN
    val elevation by animateDpAsState(if (isSelected) 4.dp else 1.dp, label = "elevation")
    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(CardDefaults.shape)
            .pointerInput(modInfo.uri, isSelectable) {
                detectTapGestures(
                    onTap = { if (isSelectable) onToggleSelection() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { if (isSelectable) onToggleSelection() }, enabled = isSelectable)
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
            val typeIcon = when (modInfo.type.lowercase()) {
                "idle" -> Icons.Default.Person
                "cutscene" -> Icons.Default.Movie
                else -> Icons.Default.Category
            }
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = modInfo.type.uppercase(),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = modInfo.type,
                        modifier = Modifier.size(14.dp)
                    )
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
