@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModScreen(
    viewModel: MainViewModel,
    onSelectModSource: () -> Unit,
    onUninstallRequest: (String) -> Unit,
    onUnpackRequest: () -> Unit,
    onMergeRequest: () -> Unit
) {
    val modSourceDirectoryUri by viewModel.modSourceDirectoryUri.collectAsState()
    val modsList by viewModel.filteredModsList.collectAsState()
    val allModsList by viewModel.modsList.collectAsState()
    val groupedMods = modsList.groupBy { it.targetHashedName ?: "Unknown" }
    val selectedMods by viewModel.selectedMods.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()


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
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedVisibility(visible = selectedMods.size == 1) {
                    ExtendedFloatingActionButton(
                        onClick = onMergeRequest,
                        icon = { Icon(Icons.Default.Merge, contentDescription = "Merge") },
                        text = { Text("Merge Spine") }
                    )
                }
                AnimatedVisibility(visible = modSourceDirectoryUri != null && selectedMods.isEmpty()) {
                    FloatingActionButton(
                        onClick = onUnpackRequest,
                    ) {
                        Icon(Icons.Default.Unarchive, contentDescription = "Unpack Bundle")
                    }
                }
                AnimatedVisibility(visible = selectedMods.isNotEmpty()) {
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
                                    val allModsCount = allModsList.size
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
                                // --- 修改點 1: 定義間隔寬度 ---
                                val spacerWidth = 8.dp

                                val astcCardWidth by transition.animateDp(
                                    label = "astc_card_width",
                                    transitionSpec = { tween(350) }
                                ) { active ->
                                    // --- 修改點 2: 計算寬度時減去間隔 ---
                                    if (active) collapsedAstcWidth else maxWidth - collapsedSearchWidth - spacerWidth
                                }

                                val searchCardWidth by transition.animateDp(
                                    label = "search_card_width",
                                    transitionSpec = { tween(350) }
                                ) { active ->
                                    // --- 修改點 3: 計算寬度時減去間隔 ---
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
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            AnimatedVisibility(
                                                visible = !isSearchActive,
                                                modifier = Modifier.weight(1f).padding(start = 12.dp)
                                            ) {
                                                Text(
                                                    "Use ASTC Compression",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1
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
                                    
                                    // --- 修改點 4: 在兩個卡片之間插入 Spacer ---
                                    Spacer(Modifier.width(spacerWidth))

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
                        }

                        if (isLoading && modsList.isEmpty()) {
                            ShimmerLoadingScreen()
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
            }
        }
    }
}