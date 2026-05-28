package com.changyow.mediadler.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.changyow.mediadler.appContainer
import com.changyow.mediadler.core.model.AudioFormat
import com.changyow.mediadler.core.model.MediaKind
import com.changyow.mediadler.core.model.ShareMode
import com.changyow.mediadler.core.model.StorageMode
import com.changyow.mediadler.core.model.VideoQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(container.settingsRepository) }
        },
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            viewModel.update { it.copy(storageMode = StorageMode.SAF, safTreeUri = uri.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingSection("分享模式") {
                ChoiceChips(
                    options = ShareMode.entries,
                    selected = settings.shareMode,
                    label = { if (it == ShareMode.ONE_TAP) "一鍵下載" else "彈窗選擇" },
                    onSelect = { mode -> viewModel.update { it.copy(shareMode = mode) } },
                )
                SwitchSetting(
                    label = "多項時自動全部下載（一鍵模式）",
                    checked = settings.downloadAllWhenMultiple,
                    onChange = { value -> viewModel.update { it.copy(downloadAllWhenMultiple = value) } },
                )
            }

            SettingSection("預設下載") {
                ChoiceChips(
                    options = MediaKind.entries,
                    selected = settings.defaultMediaKind,
                    label = { if (it == MediaKind.VIDEO) "影片" else "音訊" },
                    onSelect = { kind -> viewModel.update { it.copy(defaultMediaKind = kind) } },
                )
                DropdownSetting(
                    label = "預設畫質",
                    options = VideoQuality.entries,
                    selected = settings.defaultVideoQuality,
                    optionLabel = ::qualityLabel,
                    onSelect = { quality -> viewModel.update { it.copy(defaultVideoQuality = quality) } },
                )
                DropdownSetting(
                    label = "音訊格式",
                    options = AudioFormat.entries,
                    selected = settings.audioFormat,
                    optionLabel = { it.ext.uppercase() },
                    onSelect = { format -> viewModel.update { it.copy(audioFormat = format) } },
                )
            }

            SettingSection("儲存位置") {
                ChoiceChips(
                    options = StorageMode.entries,
                    selected = settings.storageMode,
                    label = { if (it == StorageMode.DOWNLOADS) "Downloads" else "自選資料夾" },
                    onSelect = { mode ->
                        if (mode == StorageMode.SAF) {
                            safLauncher.launch(null)
                        } else {
                            viewModel.update { it.copy(storageMode = StorageMode.DOWNLOADS) }
                        }
                    },
                )
                if (settings.storageMode == StorageMode.DOWNLOADS) {
                    Text(
                        "Download/media-dler/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        settings.safTreeUri ?: "尚未選擇資料夾",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { safLauncher.launch(null) }) { Text("選擇資料夾") }
                }
            }
        }
    }
}

private fun qualityLabel(quality: VideoQuality): String =
    quality.maxHeight?.let { "${it}p" } ?: "最佳畫質"

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun <T> DropdownSetting(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(optionLabel(selected))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
