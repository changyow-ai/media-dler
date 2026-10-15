package com.changyow.mediadler.ui.transcribe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.changyow.mediadler.TranscribeActivity
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.transcribe.TranscriptJob
import com.changyow.mediadler.transcribe.TranscriptStatus
import com.changyow.mediadler.ui.common.ScreenHeader

/** Past and in-flight jobs share the same result action and persisted state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptHistoryScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val manager = remember { context.appContainer.transcriptionManager }
    val jobs by manager.jobs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            if (onBack != null) TopAppBar(
                title = { Text("轉錄記錄") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { manager.clearAll() }, enabled = jobs.isNotEmpty()) {
                        Text("全部清除")
                    }
                },
            ) else ScreenHeader(title = "轉錄記錄", subtitle = "語音轉文字") {
                if (jobs.isNotEmpty()) {
                    TextButton(onClick = { manager.clearAll() }) { Text("全部清除") }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (jobs.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(20.dp).size(32.dp),
                                )
                            }
                            Text("還沒有轉錄記錄", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "分享影片、音訊或連結到 media-dler，選擇轉文字後即可在這裡查看。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(jobs, key = { it.id }) { job ->
                        JobRow(
                            job = job,
                            onOpen = { TranscribeActivity.start(context, job.id) },
                            onRemove = { manager.delete(job.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JobRow(job: TranscriptJob, onOpen: () -> Unit, onRemove: () -> Unit) {
    val statusColor = when (job.status) {
        TranscriptStatus.FAILED -> MaterialTheme.colorScheme.error
        TranscriptStatus.RUNNING, TranscriptStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (job.status) {
        TranscriptStatus.QUEUED -> Icons.Outlined.HourglassEmpty
        TranscriptStatus.RUNNING -> Icons.Outlined.Mic
        TranscriptStatus.COMPLETED -> Icons.Outlined.CheckCircle
        TranscriptStatus.FAILED -> Icons.Outlined.ErrorOutline
        TranscriptStatus.CANCELLED -> Icons.Outlined.Close
    }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.padding(12.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        job.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusText(job),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    job.method?.let { method ->
                        Text(
                            method,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = "移除 ${job.label}")
                }
            }
            if (job.status == TranscriptStatus.RUNNING) {
                if (job.progress > 0.051f || job.text.isNotBlank()) {
                    LinearProgressIndicator(
                        progress = { job.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (job.status == TranscriptStatus.COMPLETED && job.text.isNotBlank()) {
                Text(
                    job.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusText(job: TranscriptJob): String = when (job.status) {
    TranscriptStatus.QUEUED -> "排隊中"
    TranscriptStatus.RUNNING -> if (job.progress > 0.051f || job.text.isNotBlank()) {
        "處理中 ${(job.progress * 100).toInt()}%"
    } else {
        "處理中…"
    }
    TranscriptStatus.COMPLETED -> "完成"
    TranscriptStatus.FAILED -> "失敗：${job.error ?: "未知錯誤"}"
    TranscriptStatus.CANCELLED -> "已放棄"
}
