package com.example.bd2modmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.data.model.ModCategory
import com.example.bd2modmanager.data.model.ModInfo
import com.example.bd2modmanager.data.model.ResolutionState
import com.example.bd2modmanager.data.model.categoryOf
import com.example.bd2modmanager.data.model.isUnknownCharacter
import com.example.bd2modmanager.data.repository.CharacterMetaRepository
import com.example.bd2modmanager.ui.components.CharacterAvatar
import kotlinx.coroutines.launch

data class CharacterEntry(
    val name: String,
    val counts: Map<ModCategory, Int>,
    val isNpc: Boolean = false
) {
    val total: Int get() = counts.values.sum()
    val hasMods: Boolean get() = total > 0
}

enum class CharacterFilter(val label: String) {
    ALL("All"),
    HAS_MODS("Has Mods"),
    MALE("Male"),
    FEMALE("Female"),
    COLLAB("Collab")
}

@Composable
fun CharacterScreen(
    entries: List<CharacterEntry>,
    filter: CharacterFilter,
    searchQuery: String,
    onSelectFilter: (CharacterFilter) -> Unit,
    onPickCharacter: (String) -> Unit,
    avatarSyncFailed: Boolean = false,
    onRetryAvatarSync: () -> Unit = {}
) {
    val context = LocalContext.current
    val meta = remember(context) { CharacterMetaRepository.getInstance(context) }

    val filteredByChip = remember(entries, filter, meta) {
        when (filter) {
            CharacterFilter.HAS_MODS -> entries.filter { it.hasMods }
            CharacterFilter.MALE -> entries.filter { meta.forCharacter(it.name)?.isMale == true }
            CharacterFilter.FEMALE -> entries.filter { meta.forCharacter(it.name)?.isFemale == true }
            CharacterFilter.COLLAB -> entries.filter { meta.forCharacter(it.name)?.isCollab == true }
            CharacterFilter.ALL -> entries
        }
    }

    val shown = remember(filteredByChip, searchQuery) {
        if (searchQuery.isBlank()) {
            filteredByChip
        } else {
            val keywords = searchQuery.split(" ").filter { it.isNotBlank() }
            filteredByChip.filter { entry ->
                keywords.all { kw -> entry.name.contains(kw, ignoreCase = true) }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CharacterFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { onSelectFilter(f) },
                    label = { Text(f.label) }
                )
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        searchQuery.isNotBlank() -> "No characters match \"$searchQuery\"."
                        filter == CharacterFilter.HAS_MODS -> "No mods matched to characters yet."
                        filter == CharacterFilter.MALE -> "No matching male characters."
                        filter == CharacterFilter.FEMALE -> "No matching female characters."
                        filter == CharacterFilter.COLLAB -> "No matching collab characters."
                        else -> "Character table not loaded yet."
                    },
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

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        items(playable, key = { "playable:${it.name}" }) { entry ->
                            CharacterRow(entry = entry, onClick = { onPickCharacter(entry.name) })
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
                                CharacterRow(entry = entry, onClick = { onPickCharacter(entry.name) })
                                HorizontalDivider()
                            }
                        }
                    }

                    AlphabetIndex(
                        letters = remember(shown) { shown.map { initialOf(it.name) }.distinct() },
                        onPick = { letter ->
                            var target = playable.indexOfFirst { initialOf(it.name) == letter }
                            if (target < 0) {
                                val i = npc.indexOfFirst { initialOf(it.name) == letter }
                                if (i >= 0) target = playable.size + 1 + i
                            }
                            if (target >= 0) scope.launch { listState.animateScrollToItem(target) }
                        }
                    )
                }

                if (avatarSyncFailed) {
                    AvatarSyncHint(onRetry = onRetryAvatarSync)
                }
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

private fun initialOf(name: String): Char {
    val c = name.trim().firstOrNull() ?: '#'
    return if (c.isLetter()) c.uppercaseChar() else '#'
}

@Composable
private fun CharacterRow(
    entry: CharacterEntry,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CharacterAvatar(characterName = entry.name, size = 40.dp)

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.hasMods) FontWeight.SemiBold else FontWeight.Normal,
                color = if (entry.hasMods) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.hasMods) {
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModCategory.entries.forEach { cat ->
                        val n = entry.counts[cat] ?: 0
                        if (n > 0) CategoryBadge(cat, n)
                    }
                }
            }
        }

        if (entry.hasMods) {
            Text(
                "${entry.total}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CategoryBadge(category: ModCategory, count: Int) {
    val short = when (category) {
        ModCategory.CUTSCENE -> "Cut"
        ModCategory.ILLUST -> "Illust"
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

@Composable
private fun AlphabetIndex(letters: List<Char>, onPick: (Char) -> Unit) {
    if (letters.size < 2) return
    var height by remember { mutableIntStateOf(1) }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(26.dp)
            .padding(vertical = 6.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) {
                detectVerticalDragGestures { change, _ ->
                    val i = ((change.position.y / height) * letters.size)
                        .toInt().coerceIn(0, letters.size - 1)
                    onPick(letters[i])
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        letters.forEach { ch ->
            Text(
                ch.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onPick(ch) }
            )
        }
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
