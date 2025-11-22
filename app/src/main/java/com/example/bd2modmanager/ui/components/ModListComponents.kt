package com.example.bd2modmanager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.bd2modmanager.data.model.InstallJob
import com.example.bd2modmanager.data.model.JobStatus

@Composable
fun SelectionRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.FolderOpen, contentDescription = "Select", tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
fun InstallJobRow(installJob: InstallJob) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Target: ${installJob.job.hashedName.take(12)}...", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusIcon = when (installJob.status) {
                is JobStatus.Pending -> Icons.Default.Schedule
                is JobStatus.Downloading -> Icons.Default.Download
                is JobStatus.Installing -> Icons.Default.Build
                is JobStatus.Finished -> Icons.Default.CheckCircle
                is JobStatus.Failed -> Icons.Default.Error
            }
            Icon(statusIcon, contentDescription = "Status", modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                val progressMessage = when (val status = installJob.status) {
                    is JobStatus.Downloading -> status.progressMessage
                    is JobStatus.Installing -> status.progressMessage
                    is JobStatus.Failed -> status.error
                    is JobStatus.Finished -> "Finished successfully!"
                    is JobStatus.Pending -> "Waiting in queue..."
                }
                Text(progressMessage, style = MaterialTheme.typography.bodySmall)
                if (installJob.status is JobStatus.Downloading || installJob.status is JobStatus.Installing) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
