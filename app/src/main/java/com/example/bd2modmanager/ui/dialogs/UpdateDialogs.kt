package com.example.bd2modmanager.ui.dialogs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.update.UpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ImageViewerDialog(
    imagePaths: List<String>,
    onDismiss: () -> Unit
) {
    if (imagePaths.isEmpty()) return
    var index by remember(imagePaths) { mutableIntStateOf(0) }
    var bitmap by remember(imagePaths, index) { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current

    LaunchedEffect(imagePaths, index) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val path = imagePaths[index]
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                val screenW = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= screenW) sample *= 2
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preview Image ${index + 1}/${imagePaths.size}") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black)
                    .clickable {
                        if (index < imagePaths.size - 1) index++ else onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun UpdateDialog(
    release: UpdateRepository.Release?,
    onDownload: (UpdateRepository.Release) -> Unit,
    onIgnore: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (release == null) return

    val notes = remember(release.notes) { releaseNotesText(release.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("New Version Available: ${release.version}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    notes.ifBlank { "No release notes provided." },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = { onDownload(release) }) { Text("Download & Install") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onIgnore(release.version) }) {
                    Text("Ignore")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun UpdateReadyDialog(
    apkName: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    if (apkName == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Update Ready to Install") },
        text = {
            Text(apkName, style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { Button(onClick = onInstall) { Text("Install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val MD_LINK = Regex("""\[([^\[\]]*)\]\(([^()\s]*)\)""")

private fun releaseNotesText(raw: String): String = raw
    .lineSequence()
    .joinToString("\n") { line ->
        val linked = MD_LINK.replace(line) { m ->
            val label = m.groupValues[1].trim()
            val url = m.groupValues[2].trim()
            if (label.isEmpty()) url else "$label ($url)"
        }
        val trimmed = linked.trimStart()
        when {
            trimmed.startsWith("### ") -> trimmed.removePrefix("### ")
            trimmed.startsWith("## ") -> trimmed.removePrefix("## ")
            trimmed.startsWith("# ") -> trimmed.removePrefix("# ")
            trimmed.startsWith("* ") || trimmed.startsWith("- ") -> "• " + trimmed.drop(2)
            else -> linked
        }.replace("**", "").replace("`", "")
    }
