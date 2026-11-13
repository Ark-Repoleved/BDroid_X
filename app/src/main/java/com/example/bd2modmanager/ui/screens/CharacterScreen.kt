package com.example.bd2modmanager.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bd2modmanager.data.model.ModCategory
import com.example.bd2modmanager.data.model.ModInfo
import com.example.bd2modmanager.data.model.ResolutionState
import com.example.bd2modmanager.data.model.categoryOf
import com.example.bd2modmanager.data.model.isUnknownCharacter
import com.example.bd2modmanager.ui.components.CharacterAvatar
import kotlinx.coroutines.launch
import kotlin.math.abs

data class CharacterEntry(
    val name: String,
    val counts: Map<ModCategory, Int>,
    val isNpc: Boolean = false
) {
    val total: Int get() = counts.values.sum()
    val hasMods: Boolean get() = total > 0
}

@Composable
fun CharacterScreen(
    entries: List<CharacterEntry>,
    modsByCharacter: Map<String, List<ModInfo>>,
    selectedMods: Set<Uri>,
    searchQuery: String,
    onToggleSelectCharacter: (String) -> Unit,
    onPickCharacter: (String) -> Unit,
    avatarSyncFailed: Boolean = false,
    onRetryAvatarSync: () -> Unit = {}
) {
    val withMods = remember(entries) { entries.filter { it.hasMods } }

    val shown = remember(withMods, modsByCharacter, searchQuery) {
        if (searchQuery.isBlank()) {
            withMods
        } else {
            val keywords = searchQuery.split(" ").filter { it.isNotBlank() }
            withMods.filter { entry ->
                val charMods = modsByCharacter[entry.name].orEmpty()
                keywords.all { kw ->
                    entry.name.contains(kw, ignoreCase = true) ||
                        charMods.any {
                            it.name.contains(kw, ignoreCase = true) ||
                                it.costume.contains(kw, ignoreCase = true)
                        }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isNotBlank()) "No characters match \"$searchQuery\"."
                    else "No mods matched to characters yet.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val playable = remember(shown) { shown.filterNot { it.isNpc } }
            val npc = remember(shown) { shown.filter { it.isNpc } }
            val letters = remember(playable, npc) {
                (playable + npc).map { initialOf(it.name) }.distinct()
            }
            val visibleInitial by remember(playable, npc) {
                derivedStateOf {
                    val idx = listState.firstVisibleItemIndex
                    when {
                        idx < playable.size -> initialOf(playable[idx].name)
                        npc.isNotEmpty() -> {
                            val npcIdx = (idx - playable.size - 1).coerceIn(0, npc.lastIndex)
                            initialOf(npc[npcIdx].name)
                        }
                        else -> null
                    }
                }
            }
            var draggingLetter by remember { mutableStateOf<Char?>(null) }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        items(playable, key = { "playable:${it.name}" }) { entry ->
                            val knownUris = remember(entry.name, modsByCharacter) {
                                modsByCharacter[entry.name].orEmpty()
                                    .filter { it.resolutionState == ResolutionState.KNOWN }
                                    .map { it.uri }
                                    .toSet()
                            }
                            val selectedCount = knownUris.count { it in selectedMods }
                            val checkboxState = when {
                                selectedCount == 0 -> ToggleableState.Off
                                selectedCount == knownUris.size && knownUris.isNotEmpty() -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }
                            CharacterRow(
                                entry = entry,
                                checkboxState = checkboxState,
                                onToggleSelect = { onToggleSelectCharacter(entry.name) },
                                onClick = { onPickCharacter(entry.name) }
                            )
                            HorizontalDivider()
                        }
                        if (npc.isNotEmpty()) {
                            item(key = "npc-header") {
                                Text(
                                    "NPC",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                                )
                            }
                            items(npc, key = { "npc:${it.name}" }) { entry ->
                                val knownUris = remember(entry.name, modsByCharacter) {
                                    modsByCharacter[entry.name].orEmpty()
                                        .filter { it.resolutionState == ResolutionState.KNOWN }
                                        .map { it.uri }
                                        .toSet()
                                }
                                val selectedCount = knownUris.count { it in selectedMods }
                                val checkboxState = when {
                                    selectedCount == 0 -> ToggleableState.Off
                                    selectedCount == knownUris.size && knownUris.isNotEmpty() -> ToggleableState.On
                                    else -> ToggleableState.Indeterminate
                                }
                                CharacterRow(
                                    entry = entry,
                                    checkboxState = checkboxState,
                                    onToggleSelect = { onToggleSelectCharacter(entry.name) },
                                    onClick = { onPickCharacter(entry.name) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    AlphabetIndex(
                        letters = letters,
                        activeLetter = draggingLetter ?: visibleInitial,
                        isDragging = draggingLetter != null,
                        onPick = { letter ->
                            draggingLetter = letter
                            var target = playable.indexOfFirst { initialOf(it.name) == letter }
                            if (target < 0) {
                                val i = npc.indexOfFirst { initialOf(it.name) == letter }
                                if (i >= 0) target = playable.size + 1 + i
                            }
                            if (target >= 0) {
                                scope.launch { listState.scrollToItem(target) }
                            }
                        },
                        onDragEnd = { draggingLetter = null }
                    )
                }

                AnimatedVisibility(
                    visible = draggingLetter != null,
                    enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.85f, animationSpec = tween(120)),
                    exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.85f, animationSpec = tween(180))
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                        tonalElevation = 4.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = (draggingLetter ?: ' ').toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (avatarSyncFailed) {
                    AvatarSyncHint(onRetry = onRetryAvatarSync)
                }
            }
        }
    }
}

private fun initialOf(name: String): Char {
    val c = name.trim().firstOrNull() ?: '#'
    return if (c.isLetter()) c.uppercaseChar() else '#'
}

@Composable
private fun AlphabetIndex(
    letters: List<Char>,
    activeLetter: Char?,
    isDragging: Boolean,
    onPick: (Char) -> Unit,
    onDragEnd: () -> Unit
) {
    if (letters.size < 2) return
    var height by remember { mutableIntStateOf(1) }
    var lastPicked by remember { mutableStateOf<Char?>(null) }
    val activeIdx = remember(letters, activeLetter) {
        letters.indexOf(activeLetter)
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(32.dp)
            .padding(vertical = 10.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun pickAtY(y: Float) {
                        val idx = ((y / height) * letters.size)
                            .toInt()
                            .coerceIn(0, letters.lastIndex)
                        val ch = letters[idx]
                        if (ch != lastPicked) {
                            lastPicked = ch
                            onPick(ch)
                        }
                    }
                    pickAtY(down.position.y)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        pickAtY(change.position.y)
                        change.consume()
                    }
                    lastPicked = null
                    onDragEnd()
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        letters.forEachIndexed { index, ch ->
            val distance = if (activeIdx >= 0) abs(index - activeIdx) else Int.MAX_VALUE
            val proximity = when (distance) {
                0 -> 1f
                1 -> 0.55f
                2 -> 0.25f
                else -> 0f
            }
            val targetScale = 1f + proximity * (if (isDragging) 0.45f else 0.25f)
            val targetOffsetX = if (isDragging) -proximity * 10f else -proximity * 3f
            val targetColor = lerp(mutedColor, primaryColor, proximity)

            val animScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = tween(140),
                label = "alpha_scale"
            )
            val animOffsetX by animateFloatAsState(
                targetValue = targetOffsetX,
                animationSpec = tween(140),
                label = "alpha_offset"
            )
            val animColor by animateColorAsState(
                targetValue = targetColor,
                animationSpec = tween(140),
                label = "alpha_color"
            )

            Box(
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = animScale
                        scaleY = animScale
                        translationX = animOffsetX
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ch.toString(),
                    fontSize = 11.sp,
                    fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Medium,
                    color = animColor
                )
            }
        }
    }
}

@Composable
private fun AvatarSyncHint(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .shadow(4.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onRetry)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Check network connection",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Character avatars are fetched from GitHub",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap to retry",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CharacterRow(
    entry: CharacterEntry,
    checkboxState: ToggleableState,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CharacterAvatar(characterName = entry.name, size = 40.dp)

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModCategory.entries.forEach { cat ->
                    val n = entry.counts[cat] ?: 0
                    if (n > 0) CategoryBadge(cat, n)
                }
            }
        }

        Text(
            "${entry.total}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(4.dp))
        TriStateCheckbox(
            state = checkboxState,
            onClick = onToggleSelect
        )
    }
}

@Composable
private fun CategoryBadge(category: ModCategory, count: Int) {
    val short = when (category) {
        ModCategory.CUTSCENE -> "Cut"
        ModCategory.ILLUST -> "Idle"
        ModCategory.DATING -> "Date"
        ModCategory.OTHER -> "Other"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            "$short $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

fun buildCharacterEntries(
    allCharacters: List<String>,
    npcCharacters: List<String>,
    mods: List<ModInfo>
): List<CharacterEntry> {
    val counts = HashMap<String, HashMap<ModCategory, Int>>()
    for (m in mods) {
        if (m.resolutionState != ResolutionState.KNOWN) continue
        if (isUnknownCharacter(m.character)) continue
        val name = m.character.trim()
        val cat = categoryOf(m.type, m.costume)
        counts.getOrPut(name) { HashMap() }.merge(cat, 1, Int::plus)
    }
    val npcSet = npcCharacters.toSet()
    val names = LinkedHashSet(allCharacters).apply {
        addAll(counts.keys.filter { it !in npcSet && !it.equals("Other", true) })
    }
    val playable = names.map { n ->
        CharacterEntry(name = n, counts = counts[n] ?: emptyMap(), isNpc = false)
    }.sortedBy { it.name.lowercase() }

    val npcNames = LinkedHashSet(npcCharacters).apply {
        addAll(counts.keys.filter { it !in names })
    }.filter { it !in names }
    val npc = npcNames.map { n ->
        CharacterEntry(name = n, counts = counts[n] ?: emptyMap(), isNpc = true)
    }.sortedBy { it.name.lowercase() }

    return playable + npc
}
