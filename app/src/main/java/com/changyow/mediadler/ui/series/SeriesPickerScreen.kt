package com.changyow.mediadler.ui.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.PlaybackLine
import com.changyow.mediadler.core.model.SeriesCatalog
import com.changyow.mediadler.core.model.SourceKind
import com.changyow.mediadler.core.model.VideoQuality
import com.changyow.mediadler.ui.common.RequestNotificationPermission
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Host starts the download service and finishes its Activity in onSubmitted. */
@Composable
fun SeriesPickerScreen(url: String, onSubmitted: (Int) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val model: SeriesPickerViewModel = viewModel(key = "series:$url", factory = viewModelFactory {
        initializer {
            SeriesPickerViewModel(url, container.seriesCatalogExtractor, container.settingsRepository,
                container.downloadQueue, createSavedStateHandle())
        }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val submitted = state as? SeriesPickerViewModel.UiState.Submitted
    LaunchedEffect(submitted) { submitted?.let { onSubmitted(it.count) } }
    val submitting = (state as? SeriesPickerViewModel.UiState.Ready)?.submitting == true
    BackHandler { if (!submitting) onClose() }
    RequestNotificationPermission()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            val compact = maxHeight < 520.dp || LocalDensity.current.fontScale > 1.3f
            Column(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("選取集數", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onClose, enabled = !submitting, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "關閉")
                    }
                }
                when (val current = state) {
                    SeriesPickerViewModel.UiState.Loading,
                    is SeriesPickerViewModel.UiState.Submitted -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator()
                            Text(if (submitted == null) "正在讀取線路與集數…" else "已加入排程")
                        }
                    }
                    is SeriesPickerViewModel.UiState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(current.message, color = MaterialTheme.colorScheme.error)
                            Button(onClick = model::retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新讀取") }
                        }
                    }
                    is SeriesPickerViewModel.UiState.Ready -> SeriesPickerContent(current, compact,
                        model::showLine, model::toggleEpisode, model::selectAll, model::clearLine,
                        model::chooseQuality, model::submit)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SeriesPickerContent(
    state: SeriesPickerViewModel.UiState.Ready,
    compact: Boolean,
    onShowLine: (String) -> Unit,
    onToggle: (String, String) -> Unit,
    onSelectAll: (String) -> Unit,
    onClear: (String) -> Unit,
    onQuality: (VideoQuality) -> Unit,
    onSubmit: () -> Unit,
) {
    val lines = state.catalog.lines
    if (!compact) CatalogHeader(state.catalog, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    if (lines.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (compact) CatalogHeader(state.catalog)
                Text("目前沒有可選取的線路與集數")
            }
        }
    } else {
        val initial = lines.indexOfFirst { it.id == state.visibleLineId }.coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = initial, pageCount = { lines.size })
        val scope = rememberCoroutineScope()
        LaunchedEffect(pager, lines) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
                lines.getOrNull(index)?.let { onShowLine(it.id) }
            }
        }
        ScrollableTabRow(selectedTabIndex = pager.currentPage.coerceIn(lines.indices), edgePadding = 20.dp,
            containerColor = MaterialTheme.colorScheme.background) {
            lines.forEachIndexed { index, line ->
                Tab(selected = pager.currentPage == index, enabled = !state.submitting,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                    text = { Text(line.name, maxLines = 2, overflow = TextOverflow.Ellipsis) })
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
            userScrollEnabled = !state.submitting, key = { lines[it].id }) { index ->
            EpisodeGrid(state, lines[index], compact, onToggle, onSelectAll, onClear)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SelectionFooter(state, compact, onQuality, onSubmit)
}

@Composable
private fun CatalogHeader(catalog: SeriesCatalog, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(Modifier.size(72.dp), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            if (catalog.thumbnailUrl != null) {
                AsyncImage(catalog.thumbnailUrl, contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
            } else Box(Modifier.fillMaxSize(), Alignment.Center) {
                Icon(Icons.Default.Videocam, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(catalog.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            val source = when (catalog.kind) {
                SourceKind.GIMY -> "Gimy"
                SourceKind.EIGHT_MAPLE -> "8maple"
                SourceKind.GENERAL -> "影片"
            }
            Text("$source · ${catalog.lines.size} 條線路", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EpisodeGrid(
    state: SeriesPickerViewModel.UiState.Ready,
    line: PlaybackLine,
    compact: Boolean,
    onToggle: (String, String) -> Unit,
    onSelectAll: (String) -> Unit,
    onClear: (String) -> Unit,
) {
    val selected = if (state.selection.lineId == line.id) state.selection.urls else emptySet()
    val allSelected = line.episodes.isNotEmpty() && line.episodes.all { it.page.playbackUrl in selected }
    val minCell = (96 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    LazyVerticalGrid(columns = GridCells.Adaptive(minCell), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (compact) item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            CatalogHeader(state.catalog, Modifier.padding(bottom = 4.dp))
        }
        item(key = "line", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(line.name, style = MaterialTheme.typography.titleMedium)
                    Text("${line.episodes.size} 集", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { if (allSelected) onClear(line.id) else onSelectAll(line.id) },
                    enabled = line.episodes.isNotEmpty() && !state.submitting,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (allSelected) "清除全選" else "全選") }
            }
        }
        if (compact) item(key = "guidance", span = { GridItemSpan(maxLineSpan) }) {
            Text("勾選其他線路的集數會清除原選擇；切換頁面不影響選取。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (line.episodes.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Text("這條線路目前沒有集數，可切換其他線路。", Modifier.padding(vertical = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(line.episodes.distinctBy { it.page.playbackUrl }, key = { it.page.playbackUrl }) { episode ->
            val checked = episode.page.playbackUrl in selected
            Surface(shape = MaterialTheme.shapes.medium,
                color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (checked) 2.dp else 1.dp,
                    if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
                    .toggleable(checked, enabled = !state.submitting, role = Role.Checkbox,
                        onValueChange = { onToggle(line.id, episode.page.playbackUrl) })) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(episode.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                    if (checked) Icon(Icons.Default.CheckCircle, contentDescription = null,
                        modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SelectionFooter(state: SeriesPickerViewModel.UiState.Ready, compact: Boolean,
    onQuality: (VideoQuality) -> Unit, onSubmit: () -> Unit) {
    val count = state.selection.urls.size
    val selectedLine = state.catalog.lines.firstOrNull { it.id == state.selection.lineId }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        val wide = maxWidth >= 580.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (count == 0) "尚未選取集數" else "已選 $count 集", style = MaterialTheme.typography.titleMedium)
                    selectedLine?.let { Text(it.name, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                QualityMenu(state.quality, !state.submitting, onQuality)
                if (wide) SubmitButton(count, state.submitting, onSubmit, Modifier.weight(1f))
            }
            if (!compact) Text("勾選其他線路的集數會清除原選擇", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.submitError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (!wide) SubmitButton(count, state.submitting, onSubmit, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SubmitButton(count: Int, submitting: Boolean, onSubmit: () -> Unit, modifier: Modifier) {
    Button(onClick = onSubmit, enabled = count > 0 && !submitting, modifier = modifier.heightIn(min = 52.dp)) {
        Text(if (submitting) "正在加入排程…" else "加入排程（$count）")
    }
}

@Composable
private fun QualityMenu(quality: VideoQuality, enabled: Boolean, onQuality: (VideoQuality) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(quality.label(), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VideoQuality.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label()) },
                    onClick = { expanded = false; onQuality(option) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

private fun VideoQuality.label(): String = maxHeight?.let { "${it}p" } ?: "最佳畫質"
