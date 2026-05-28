package com.changyow.mediadler.ui.picker

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.ui.common.RequestNotificationPermission
import com.changyow.mediadler.core.model.AudioFormat
import com.changyow.mediadler.core.model.DownloadRequest
import com.changyow.mediadler.core.model.FormatSelection
import com.changyow.mediadler.core.model.MediaItem

@Composable
fun ShareSheet(url: String, onSubmitted: () -> Unit, onClose: () -> Unit) {
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
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.padding(20.dp)) {
                when (val s = state) {
                    PickerViewModel.UiState.Loading -> LoadingContent()
                    is PickerViewModel.UiState.Error -> ErrorContent(s.message, onClose)
                    is PickerViewModel.UiState.Picker -> PickerContent(s.items, viewModel::submit, onClose)
                    is PickerViewModel.UiState.Submitted -> LaunchedEffect(s.count) {
                        Toast.makeText(context, "已加入 ${s.count} 個下載", Toast.LENGTH_SHORT).show()
                        onSubmitted()
                        onClose()
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text("解析中…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorContent(message: String, onClose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("無法下載", style = MaterialTheme.typography.titleMedium)
        SelectionContainer(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("關閉") }
        }
    }
}

@Composable
private fun PickerContent(
    items: List<MediaItem>,
    onConfirm: (List<DownloadRequest>) -> Unit,
    onCancel: () -> Unit,
) {
    val checked = remember(items) { mutableStateListOf<Boolean>().apply { addAll(List(items.size) { true }) } }
    val choice = remember(items) { mutableStateListOf<Int>().apply { addAll(List(items.size) { 0 }) } }
    val optionLists = remember(items) { items.map { optionsFor(it) } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("選擇要下載的項目", style = MaterialTheme.typography.titleLarge)
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
        val selectedCount = checked.count { it }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel) { Text("取消") }
            Button(
                onClick = {
                    val requests = items.indices
                        .filter { checked[it] }
                        .map { i -> DownloadRequest(items[i], optionLists[i][choice[i]].second) }
                    onConfirm(requests)
                },
                enabled = selectedCount > 0,
            ) { Text("下載（$selectedCount）") }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OptionDropdown(options = options, chosen = chosen, onChoose = onChoose)
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
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) {
            Text(options[chosen].first)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option.first) },
                    onClick = {
                        onChoose(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun optionsFor(item: MediaItem): List<Pair<String, FormatSelection>> {
    if (item.isImage) return listOf("圖片" to FormatSelection.ImageOriginal)
    val maxHeight = item.formats.mapNotNull { it.height }.maxOrNull()
    val caps = listOf(1080, 720, 480, 360).filter { maxHeight == null || it <= maxHeight }
    return buildList {
        add("最佳畫質" to FormatSelection.BestVideo)
        caps.forEach { add("${it}p" to FormatSelection.CappedVideo(it)) }
        add("音訊 MP3" to FormatSelection.Audio(AudioFormat.MP3))
        add("音訊 M4A" to FormatSelection.Audio(AudioFormat.M4A))
    }
}
