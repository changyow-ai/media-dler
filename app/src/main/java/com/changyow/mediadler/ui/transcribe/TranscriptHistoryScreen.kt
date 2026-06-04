package com.changyow.mediadler.ui.transcribe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

/** A simple list of past + in-flight transcription jobs. Tap to open the result; remove or clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.appContainer.transcriptionManager }
    val jobs by manager.jobs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("轉錄記錄") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { manager.clearAll() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "全部清除")
                    }
                },
            )
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "還沒有轉錄記錄",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

@Composable
private fun JobRow(job: TranscriptJob, onOpen: () -> Unit, onRemove: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    job.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusText(job),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "移除")
            }
        }
    }
}

private fun statusText(job: TranscriptJob): String = when (job.status) {
    TranscriptStatus.QUEUED -> "排隊中"
    TranscriptStatus.RUNNING -> "處理中 ${(job.progress * 100).toInt()}%"
    TranscriptStatus.COMPLETED -> job.text.take(40).ifBlank { "完成" }
    TranscriptStatus.FAILED -> "失敗：${job.error ?: "未知錯誤"}"
    TranscriptStatus.CANCELLED -> "已放棄"
}
