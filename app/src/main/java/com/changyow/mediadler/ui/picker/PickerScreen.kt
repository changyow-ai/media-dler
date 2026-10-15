package com.changyow.mediadler.ui.picker

import android.widget.Toast
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.AudioFormat
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaItem
import com.changyow.mediadler.ui.common.RequestNotificationPermission

@Composable
fun ShareSheet(
    url: String,
    onSubmitted: () -> Unit,
    onTranscribe: () -> Unit,
    onClose: () -> Unit,
    forcePicker: Boolean = false,
) {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val viewModel: PickerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PickerViewModel(
                    url = url,
                    extractor = container.mediaExtractor,
                    settings = container.settingsRepository,
                    queue = container.downloadQueue,
                    forcePicker = forcePicker,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    PickerDialog(onClose = onClose) {
        when (val s = state) {
            PickerViewModel.UiState.Loading -> LoadingContent(onClose)
            is PickerViewModel.UiState.Error -> ErrorContent(s.message, onTranscribe, onClose)
            is PickerViewModel.UiState.Picker ->
                PickerContent(s.items, viewModel::submit, onTranscribe, onClose)
            is PickerViewModel.UiState.Submitted -> LaunchedEffect(s.count) {
                Toast.makeText(context, "已加入 ${s.count} 個下載", Toast.LENGTH_SHORT).show()
                onSubmitted()
                onClose()
            }
        }
    }
}

/** A bounded dialog shared by link and local-media choosers. */
@Composable
internal fun PickerDialog(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var surfaceBounds by remember { mutableStateOf(Rect.Zero) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = true),
    ) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            // Compose 1.7's custom-width path replaces the fitted height with screenHeightDp.
            // Keep native measurement and size the window through the public window provider.
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onClose) {
                    detectTapGestures { position ->
                        if (!surfaceBounds.contains(position)) onClose()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The platform supplies a system/IME-fitted viewport; bound the whole chooser to it.
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .onGloballyPositioned { surfaceBounds = it.boundsInRoot() },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(content = content)
            }
        }
    }
}

@Composable
internal fun PickerHeader(title: String, subtitle: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "關閉")
        }
    }
}

@Composable
private fun LoadingContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PickerHeader("解析媒體", "正在取得可下載的項目，請稍候。", onClose)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            Text("解析中…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onTranscribe: () -> Unit, onClose: () -> Unit) {
    Column {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PickerHeader("無法下載", "你仍可嘗試將這個連結轉成文字。", onClose)
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                SelectionContainer(Modifier.padding(16.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTranscribe, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("改用轉文字")
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("關閉") }
        }
    }
}

@Composable
private fun PickerContent(
    items: List<MediaItem>,
    onConfirm: (List<DownloadRequest>) -> Unit,
    onTranscribe: () -> Unit,
    onCancel: () -> Unit,
) {
    val checked = rememberSaveable(items, saver = listSaver<SnapshotStateList<Boolean>, Boolean>(
        save = { it.toList() }, restore = { it.toMutableStateList() },
    )) { mutableStateListOf<Boolean>().apply { addAll(List(items.size) { true }) } }
    val choice = rememberSaveable(items, saver = listSaver<SnapshotStateList<Int>, Int>(
        save = { it.toList() }, restore = { it.toMutableStateList() },
    )) { mutableStateListOf<Int>().apply { addAll(List(items.size) { 0 }) } }
    val optionLists = remember(items) { items.map { optionsFor(it) } }
    val selectedCount = checked.count { it }
    val allSelected = selectedCount == items.size

    Column {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PickerHeader("選擇下載項目", "選擇要儲存的媒體與格式。", onCancel)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "共 ${items.size} 個項目 · 已選 $selectedCount 個",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { checked.indices.forEach { checked[it] = !allSelected } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (allSelected) "取消全選" else "全選") }
                }
            }
            itemsIndexed(items) { index, item ->
                ItemRow(
                    item = item,
                    checked = checked[index],
                    onCheck = { checked[index] = it },
                    options = optionLists[index],
                    chosen = choice[index],
                    onChoose = { choice[index] = it },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val requests = items.indices
                        .filter { checked[it] }
                        .map { i -> DownloadRequest(items[i], optionLists[i][choice[i]].second) }
                    onConfirm(requests)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = selectedCount > 0,
            ) { Text("下載（$selectedCount）") }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                OutlinedButton(onClick = onTranscribe, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("轉文字") }
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: MediaItem,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    options: List<Pair<String, FormatSelection>>,
    chosen: Int,
    onChoose: (Int) -> Unit,
) {
    val isAudio = !item.isImage && item.formats.none { it.hasVideo } && item.formats.any { it.hasAudio }
    val type = when { item.isImage -> "圖片"; isAudio -> "音訊"; else -> "影片" }
    val fallback = when { item.isImage -> Icons.Filled.Image; isAudio -> Icons.Filled.Audiotrack; else -> Icons.Filled.Videocam }
    var thumbnailLoaded by remember(item.thumbnailUrl) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheck),
        shape = RoundedCornerShape(20.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!thumbnailLoaded) Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { thumbnailLoaded = true },
                        onError = { thumbnailLoaded = false },
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title.ifBlank { "未命名$type" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(48.dp))
            }
            if (item.isImage) {
                Text(
                    "圖片原檔",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OptionDropdown(options = options, chosen = chosen, onChoose = onChoose)
            }
        }
    }
}

@Composable
private fun OptionDropdown(
    options: List<Pair<String, FormatSelection>>,
    chosen: Int,
    onChoose: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(options[chosen].first, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option.first) },
                    onClick = {
                        onChoose(index)
                        expanded = false
                    },
                    trailingIcon = {
                        if (index == chosen) Icon(Icons.Filled.Check, contentDescription = "已選取")
                    },
                )
            }
        }
    }
}

private fun optionsFor(item: MediaItem): List<Pair<String, FormatSelection>> {
    if (item.isImage) return listOf("圖片原檔" to FormatSelection.ImageOriginal)
    val maxHeight = item.formats.mapNotNull { it.height }.maxOrNull()
    val caps = listOf(1080, 720, 480, 360).filter { maxHeight == null || it <= maxHeight }
    return buildList {
        add("最佳畫質" to FormatSelection.BestVideo)
        caps.forEach { add("${it}p" to FormatSelection.CappedVideo(it)) }
        add("音訊 MP3" to FormatSelection.Audio(AudioFormat.MP3))
        add("音訊 M4A" to FormatSelection.Audio(AudioFormat.M4A))
    }
}
