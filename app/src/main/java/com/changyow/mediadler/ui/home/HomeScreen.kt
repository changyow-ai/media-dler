package com.changyow.mediadler.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.changyow.mediadler.ShareReceiverActivity
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.extract.UrlExtractor
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.isTerminal
import com.changyow.mediadler.download.DownloadService
import com.changyow.mediadler.ui.common.RequestNotificationPermission
import com.changyow.mediadler.ui.common.ScreenHeader
import java.io.File

private enum class DownloadFilter(val label: String) {
    ALL("全部"), ACTIVE("進行中"), COMPLETED("已完成"), FAILED("失敗");

    fun includes(task: DownloadTask): Boolean = when (this) {
        ALL -> true
        ACTIVE -> !task.status.isTerminal
        COMPLETED -> task.status == DownloadStatus.COMPLETED
        FAILED -> task.status == DownloadStatus.FAILED
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.downloadQueue, container.historyStore, container.previewStore) }
        },
    )
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    val visibleTasks = tasks.filter(filter::includes)

    RequestNotificationPermission()

    Scaffold(
        topBar = {
            ScreenHeader(title = "下載", subtitle = "media-dler") {
                TextButton(
                    onClick = { viewModel.clearFinished() },
                    enabled = tasks.any { it.status.isTerminal },
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("清除已結束", modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "paste") { PastePanel { pasteUrlAndDownload(context) } }
                item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(DownloadFilter.entries, key = { it.name }) { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text("${option.label} ${tasks.count(option::includes)}") },
                            )
                        }
                    }
                }
                item(key = "heading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("下載記錄", style = MaterialTheme.typography.titleMedium)
                        Text("${visibleTasks.size} 項", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (visibleTasks.isEmpty()) {
                    item(key = "empty") { EmptyState(isFirstDownload = tasks.isEmpty()) }
                }
                items(visibleTasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onOpen = { openFile(context, task) },
                        onRetry = { if (viewModel.retry(task.id)) DownloadService.start(context) },
                        onRemove = { viewModel.remove(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PastePanel(onPaste: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("儲存喜歡的內容", style = MaterialTheme.typography.titleLarge)
            Text("複製連結，或從其他 App 分享過來", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onPaste, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("貼上連結", modifier = Modifier.padding(start = 8.dp))
            }
            Text("影片 · 圖片 · 音訊", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(isFirstDownload: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Text(if (isFirstDownload) "還沒有下載項目" else "這裡目前沒有項目",
            style = MaterialTheme.typography.titleMedium)
        Text(
            if (isFirstDownload) "貼上第一個連結，下載的檔案就會顯示在這裡。" else "試試其他篩選，查看你的下載記錄。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TaskRow(task: DownloadTask, onOpen: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val failed = task.status == DownloadStatus.FAILED
    val active = !task.status.isTerminal
    val statusColor = when {
        failed -> MaterialTheme.colorScheme.error
        task.status == DownloadStatus.COMPLETED || active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val mediaIcon = when {
        task.mimeType?.startsWith("image/") == true || task.formatLabel == "Image" -> Icons.Filled.Image
        task.mimeType?.startsWith("audio/") == true || task.formatLabel in listOf("MP3", "M4A") -> Icons.Filled.AudioFile
        else -> Icons.Filled.VideoFile
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(mediaIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    AsyncImage(
                        model = task.previewPath?.let(::File) ?: task.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val format = when (task.formatLabel) {
                        "Best" -> "最佳畫質"
                        "Image" -> "圖片原檔"
                        else -> task.formatLabel
                    }
                    Text(format,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "下載項目選單")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("移除紀錄") },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                            onClick = { showMenu = false; onRemove() },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when {
                        failed -> Icons.Filled.ErrorOutline
                        task.status == DownloadStatus.COMPLETED -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.Schedule
                    },
                    contentDescription = null, tint = statusColor, modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(statusText(task), style = MaterialTheme.typography.labelLarge, color = statusColor)
                    if (failed) {
                        Text(task.errorMessage ?: "未知錯誤", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (task.status == DownloadStatus.COMPLETED) {
                    FilledTonalIconButton(onClick = onOpen) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "開啟檔案", modifier = Modifier.size(20.dp))
                    }
                } else if (failed) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("重試", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (active) {
                if (task.status == DownloadStatus.DOWNLOADING && task.progress in 0f..1f) {
                    LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
    }
}

private fun statusText(task: DownloadTask): String = when (task.status) {
    DownloadStatus.QUEUED -> "排隊中"
    DownloadStatus.EXTRACTING -> "解析中"
    DownloadStatus.DOWNLOADING ->
        if (task.progress in 0f..1f) "下載中 ${(task.progress * 100).toInt()}%" else "下載中"
    DownloadStatus.PROCESSING -> "處理中"
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.FAILED -> "下載失敗"
    DownloadStatus.CANCELED -> "已取消"
}

private fun pasteUrlAndDownload(context: Context) {
    val clip = context.getSystemService<ClipboardManager>()?.primaryClip
    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    val url = UrlExtractor.firstUrl(text)
    if (url == null) {
        Toast.makeText(context, "剪貼簿沒有可用的連結", Toast.LENGTH_SHORT).show()
        return
    }
    context.startActivity(
        Intent(context, ShareReceiverActivity::class.java).putExtra(Intent.EXTRA_TEXT, url),
    )
}

private fun openFile(context: Context, task: DownloadTask) {
    val uri = task.outputUri ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uri), task.mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
