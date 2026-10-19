package com.changyow.mediadler.ui.home

import android.content.ClipboardManager
import android.content.ClipData
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.changyow.mediadler.ShareReceiverActivity
import com.changyow.mediadler.TaskTranscribeActivity
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.extract.UrlExtractor
import com.changyow.mediadler.core.model.DownloadStatus
import com.changyow.mediadler.core.model.DownloadTask
import com.changyow.mediadler.core.model.EpisodeMetadata
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.model.isActive
import com.changyow.mediadler.core.model.isTerminal
import com.changyow.mediadler.download.DownloadService
import com.changyow.mediadler.download.DownloadNowResult
import com.changyow.mediadler.ui.common.RequestNotificationPermission
import com.changyow.mediadler.ui.common.ScreenHeader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    val retryableFailedCount by viewModel.retryableFailedCount.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pending = scheduledHomeTasks(tasks)
    var pendingCommands by remember { mutableStateOf<Map<String, HomeTaskCommand>>(emptyMap()) }

    fun submitTaskCommand(task: DownloadTask, command: HomeTaskCommand) {
        if (task.id in pendingCommands) return
        pendingCommands = pendingCommands + (task.id to command)
        scope.launch {
            try {
                when (command) {
                    HomeTaskCommand.PAUSE -> {
                        if (!container.downloadRunner.pauseTask(task.id)) {
                            Toast.makeText(context, "目前無法暫停此項目", Toast.LENGTH_SHORT).show()
                        }
                    }
                    HomeTaskCommand.RESUME -> {
                        if (container.downloadRunner.resumeTask(task.id)) DownloadService.start(context)
                        else Toast.makeText(context, "目前無法繼續此項目", Toast.LENGTH_SHORT).show()
                    }
                    HomeTaskCommand.DOWNLOAD_NOW -> {
                        val result = container.downloadRunner.downloadNow(task.id)
                        if (result != DownloadNowResult.REJECTED) DownloadService.start(context)
                        val message = when (result) {
                            DownloadNowResult.READY -> "已安排立即下載"
                            DownloadNowResult.PREEMPTING -> "正在切換下載"
                            DownloadNowResult.AFTER_PROCESSING -> "目前檔案處理／儲存完成後開始下載"
                            DownloadNowResult.REJECTED -> "目前無法立即下載此項目"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "操作未完成，請重試", Toast.LENGTH_SHORT).show()
            } finally {
                pendingCommands = pendingCommands - task.id
            }
        }
    }
    val visibleTasks = tasks.filter(filter::includes)
    val pause = tasks.firstNotNullOfOrNull { it.queuePause }

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
                if (pause != null) {
                    item(key = "paused") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (pause.kind == com.changyow.mediadler.core.model.QueuePauseKind.NETWORK)
                                    "等待網路恢復" else "排程已暫停", style = MaterialTheme.typography.titleSmall)
                                Text(pause.message, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { DownloadService.start(context, resumeAll = true) }) { Text("繼續排程") }
                            }
                        }
                    }
                }
                if (retryableFailedCount > 0) {
                    item(key = "retry_all_failed") {
                        OutlinedButton(
                            onClick = { if (viewModel.retryAllFailed() > 0) DownloadService.start(context) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("重試全部失敗（$retryableFailedCount）", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
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
                        onCancel = { viewModel.cancel(task.id) },
                        onPause = { submitTaskCommand(task, HomeTaskCommand.PAUSE) },
                        onResume = { submitTaskCommand(task, HomeTaskCommand.RESUME) },
                        onDownloadNow = { submitTaskCommand(task, HomeTaskCommand.DOWNLOAD_NOW) },
                        pendingCommand = pendingCommands[task.id],
                        onRemove = { viewModel.remove(task.id) },
                        onEdit = { editingId = task.id },
                        queueNumber = pending.indexOfFirst { it.id == task.id }.takeIf { it >= 0 }?.plus(1),
                    )
                }
            }
        }
    }
    tasks.firstOrNull { it.id == editingId }?.let { task ->
        MetadataDialog(task, onDismiss = { editingId = null }, onSave = { metadata, done ->
            scope.launch {
                try {
                    viewModel.editMetadata(task, metadata)
                    done(null)
                    editingId = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    done(error.message ?: "無法保存修改，請重試")
                }
            }
        })
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
private fun TaskRow(task: DownloadTask, onOpen: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onDownloadNow: () -> Unit, pendingCommand: HomeTaskCommand?,
    onRemove: () -> Unit, onEdit: () -> Unit, queueNumber: Int?) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showError by remember(task.id) { mutableStateOf(false) }
    val busy = pendingCommand != null || task.interruption != null
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
                    sourceMetadata(task)?.let { metadata ->
                        Text(metadata, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
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
                        if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.PAUSED) {
                            DropdownMenuItem(text = { Text("立即下載") }, enabled = !busy && canDownloadTaskNow(task),
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                onClick = { showMenu = false; onDownloadNow() })
                        }
                        if (failed) {
                            DropdownMenuItem(text = { Text("查看完整失敗原因") },
                                onClick = { showMenu = false; showError = true })
                        }
                        DropdownMenuItem(text = { Text("複製原始連結") }, onClick = {
                            showMenu = false
                            context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("原始連結", task.sourceUrl))
                            Toast.makeText(context, "已複製原始連結", Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text("開啟原始網頁") }, onClick = {
                            showMenu = false
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(task.sourceUrl))) }
                                .onFailure { Toast.makeText(context, "無法開啟原始網頁", Toast.LENGTH_SHORT).show() }
                        })
                        DropdownMenuItem(text = { Text("手動選擇格式") }, enabled = !busy, onClick = {
                            showMenu = false
                            context.startActivity(Intent(context, ShareReceiverActivity::class.java)
                                .putExtra(Intent.EXTRA_TEXT, task.pageUrl)
                                .putExtra(ShareReceiverActivity.EXTRA_MANUAL_PICKER, true))
                        })
                        DropdownMenuItem(text = { Text("轉文字") }, enabled = !busy && transcriptSourceFor(task) != null, onClick = {
                            showMenu = false
                            transcribeTask(context, task)
                        })
                        if (task.sourceKind != SourceKind.GENERAL) {
                            DropdownMenuItem(text = { Text("修正劇集資料") }, enabled = !busy && !task.status.isActive,
                                onClick = { showMenu = false; onEdit() })
                        }
                        if (active) {
                            DropdownMenuItem(text = { Text("取消下載") }, enabled = !task.cancelRequested,
                                onClick = { showMenu = false; onCancel() })
                        }
                        DropdownMenuItem(
                            text = { Text("移除紀錄") },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                            enabled = !busy,
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
                    Text(taskStatusText(task, queueNumber, pendingCommand), style = MaterialTheme.typography.labelLarge, color = statusColor)
                    if (failed) {
                        Text(task.errorMessage ?: "未知錯誤", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else {
                        taskProgressDetail(task)?.let { detail ->
                            Text(detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (task.status == DownloadStatus.PAUSED) {
                    TextButton(onClick = onResume, enabled = !busy && canResumeTask(task)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("繼續下載", modifier = Modifier.padding(start = 4.dp))
                    }
                } else if (task.status in setOf(DownloadStatus.QUEUED, DownloadStatus.EXTRACTING, DownloadStatus.DOWNLOADING)) {
                    IconButton(onClick = onPause, enabled = !busy && canPauseTask(task)) {
                        Icon(Icons.Filled.Pause, contentDescription = "暫停下載", modifier = Modifier.size(22.dp))
                    }
                } else if (task.status == DownloadStatus.COMPLETED) {
                    FilledTonalIconButton(onClick = onOpen) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "開啟檔案", modifier = Modifier.size(20.dp))
                    }
                } else if (failed || task.status == DownloadStatus.CANCELED) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("重試", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (active && (task.status != DownloadStatus.PAUSED || task.progress in 0f..1f)) {
                if (task.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED) && task.progress in 0f..1f) {
                    LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
    }
    if (showError) {
        AlertDialog(
            onDismissRequest = { showError = false },
            title = { Text("下載失敗") },
            text = {
                Text(task.errorMessage ?: "未知錯誤", modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { showError = false }) { Text("關閉") } },
        )
    }
}

private fun sourceMetadata(task: DownloadTask): String? {
    val source = when (task.sourceKind) {
        SourceKind.GIMY -> "Gimy"
        SourceKind.EIGHT_MAPLE -> "8maple"
        SourceKind.GENERAL -> return null
    }
    return listOfNotNull(source, task.seriesTitle, task.seasonNumber?.let { "第 $it 季" },
        task.episodeNumber?.let { "第 $it 集" }, task.lineName ?: task.lineId?.let { "線路 $it" }).joinToString(" · ")
}

@Composable
private fun MetadataDialog(task: DownloadTask, onDismiss: () -> Unit,
    onSave: (EpisodeMetadata, (String?) -> Unit) -> Unit) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var series by rememberSaveable(task.id) { mutableStateOf(task.seriesTitle.orEmpty()) }
    var season by rememberSaveable(task.id) { mutableStateOf(task.seasonNumber?.toString().orEmpty()) }
    var episode by rememberSaveable(task.id) { mutableStateOf(task.episodeNumber?.toString().orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun validNumber(text: String) = text.isBlank() || text.trim().toIntOrNull()?.let { it > 0 } == true
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("修正劇集資料") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("更新記錄資訊；已建立的檔案不會搬動。留空的季數、集數表示未知。",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(title, { title = it }, label = { Text("標題") }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth(), singleLine = true, isError = title.isBlank())
                OutlinedTextField(series, { series = it }, label = { Text("影集名稱（選填）") }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(season, { season = it }, label = { Text("季數（選填）") }, enabled = !saving,
                        modifier = Modifier.weight(1f), singleLine = true, isError = !validNumber(season),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(episode, { episode = it }, label = { Text("集數（選填）") }, enabled = !saving,
                        modifier = Modifier.weight(1f), singleLine = true, isError = !validNumber(episode),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (!validNumber(season) || !validNumber(episode)) {
                    Text("季數與集數請輸入正整數，或留空表示未知。", color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && !task.status.isActive && task.interruption == null && title.isNotBlank() && validNumber(season) && validNumber(episode),
                modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    saving = true
                    error = null
                    onSave(EpisodeMetadata(title.trim(), series.trim().ifBlank { null },
                        season.trim().toIntOrNull(), episode.trim().toIntOrNull())) { message ->
                        saving = false
                        error = message
                    }
                }) { Text(if (saving) "儲存中…" else "儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
    )
}

private fun transcribeTask(context: Context, task: DownloadTask) {
    val source = transcriptSourceFor(task) ?: return
    val intent = Intent(context, TaskTranscribeActivity::class.java)
        .putExtra(ShareReceiverActivity.EXTRA_TRANSCRIBE_ONLY, true)
    if (source.isUrl) intent.putExtra(Intent.EXTRA_TEXT, source.uri) else {
        intent.action = Intent.ACTION_SEND
        intent.type = task.mimeType
        intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(source.uri))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
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
