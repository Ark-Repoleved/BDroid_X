package com.example.bd2modmanager.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.data.repository.AvatarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CharacterAvatar(
    characterName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = 8.dp
) {
    val context = LocalContext.current
    val repo = remember(context) { AvatarRepository.getInstance(context) }
    val epoch by repo.epoch.collectAsState()

    val initial = remember(characterName) {
        repo.peekAvatar(characterName)?.let { (key, _) -> repo.peek(key) }
    }
    var image by remember(characterName) { mutableStateOf(initial) }
    var settled by remember(characterName) { mutableStateOf(initial != null) }

    LaunchedEffect(characterName, epoch) {
        if (image == null) {
            val resolved = withContext(Dispatchers.IO) { repo.avatarFor(characterName) }
            val loaded = resolved?.let { repo.load(it.first, it.second) }
            if (loaded != null) {
                image = loaded
            }
            settled = true
        }
    }

    AvatarFrame(
        image = image,
        size = size,
        cornerRadius = cornerRadius,
        contentDescription = characterName,
        fallbackText = if (settled) initialOf(characterName) else null,
        modifier = modifier
    )
}

private fun initialOf(name: String): String {
    val c = name.trim().firstOrNull() ?: return "?"
    return if (c.isLetter()) c.uppercaseChar().toString() else "?"
}

@Composable
fun SkinThumbnail(
    fileId: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    cornerRadius: Dp = 6.dp,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val repo = remember(context) { AvatarRepository.getInstance(context) }
    val epoch by repo.epoch.collectAsState()

    var image by remember(fileId) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(fileId, epoch) {
        if (fileId != null && image == null) {
            val resolved = withContext(Dispatchers.IO) { repo.illustForFileId(fileId) }
            if (resolved != null) {
                val loaded = repo.load(resolved.first, resolved.second)
                if (loaded != null) image = loaded
            }
        }
    }

    AvatarFrame(image, size, cornerRadius, contentDescription, null, modifier)
}

@Composable
private fun AvatarFrame(
    image: ImageBitmap?,
    size: Dp,
    cornerRadius: Dp,
    contentDescription: String?,
    fallbackText: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
            fallbackText != null -> Text(
                fallbackText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Icon(
                Icons.Default.Person,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}
