package com.example.bd2modmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.service.RecoveryNotice

@Composable
fun RecoveryBanner(
    notices: List<RecoveryNotice>,
    onRetryMove: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (notices.isEmpty()) return

    val hasUnreadable = notices.any { it is RecoveryNotice.Unreadable }
    val canRetryMove = notices.any {
        it is RecoveryNotice.PendingMove ||
            (it is RecoveryNotice.InterruptedMove && it.hasStagedFiles)
    }

    val title = when {
        notices.any { it is RecoveryNotice.InterruptedMove } -> "Interrupted Game File Move"
        notices.any { it is RecoveryNotice.PendingMove } -> "Staged Mods Ready to Move"
        notices.any { it is RecoveryNotice.InterruptedRepack } -> "Interrupted Repack Detected"
        else -> "Install Journal Unreadable"
    }

    val body = notices.joinToString("\n") { n ->
        when (n) {
            is RecoveryNotice.InterruptedRepack ->
                "• Repack ${n.operationId} (${n.bundleNames.size} bundle(s)) stopped before writing to the game. Game files were not modified."
            is RecoveryNotice.PendingMove ->
                "• ${n.bundleNames.size} repacked bundle(s) are still staged in Download/Shared and waiting to move to the game directory."
            is RecoveryNotice.InterruptedMove ->
                "• Move ${n.operationId} (${n.bundleNames.size} bundle(s)) was interrupted while copying to the game directory. Retry the move or verify game launch."
            is RecoveryNotice.Unreadable ->
                "• ${n.message}"
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = if (hasUnreadable) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (hasUnreadable) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = if (hasUnreadable) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(if (hasUnreadable) "Reset Journal" else "Dismiss")
                }
                if (canRetryMove) {
                    Button(onClick = onRetryMove) {
                        Text("Move to Game")
                    }
                }
            }
        }
    }
}
