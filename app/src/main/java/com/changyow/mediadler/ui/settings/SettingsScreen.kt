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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.changyow.mediadler.core.model.TranscribeEngine
import com.changyow.mediadler.core.model.TranscribeLanguage
import com.changyow.mediadler.core.model.TranscribeModel
import com.changyow.mediadler.core.model.VideoQuality
import com.changyow.mediadler.util.NetworkStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.settingsRepository,
                    container.engine,
                    container.whisperModelManager,
                    container.transcriptionManager,
                )
            }
        },
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val updating by viewModel.updating.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    var confirmMeteredDownload by remember { mutableStateOf(false) }
    // On Wi-Fi just download; on mobile data ask first (gate-as-prompt, not a hard block).
    val requestModelDownload = {
        if (NetworkStatus.isMetered(context)) confirmMeteredDownload = true else viewModel.downloadModel()
    }

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

            SettingSection("下載引擎") {
                Text(
                    engineStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchSetting(
                    label = "啟動時自動更新 yt-dlp",
                    checked = settings.autoUpdateYtDlpOnStartup,
                    onChange = { value -> viewModel.update { it.copy(autoUpdateYtDlpOnStartup = value) } },
                )
                OutlinedButton(onClick = { viewModel.updateEngine() }, enabled = !updating) {
                    Text(if (updating) "更新中…" else "更新 yt-dlp")
                }
            }

            SettingSection("語音轉文字") {
                ChoiceChips(
                    options = TranscribeEngine.entries,
                    selected = settings.transcribeEngine,
                    label = { if (it == TranscribeEngine.ON_DEVICE) "裝置端" else "雲端" },
                    onSelect = { e -> viewModel.update { it.copy(transcribeEngine = e) } },
                )
                DropdownSetting(
                    label = "轉錄語言",
                    options = TranscribeLanguage.entries,
                    selected = settings.transcribeLanguage,
                    optionLabel = { it.label },
                    onSelect = { lang -> viewModel.update { it.copy(transcribeLanguage = lang) } },
                )
                Text(
                    "鎖定主要語言可避免短片頭被誤判；夾雜的其他語言仍會照原文輸出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (settings.transcribeEngine == TranscribeEngine.ON_DEVICE) {
                    DropdownSetting(
                        label = "模型",
                        options = TranscribeModel.entries,
                        selected = settings.transcribeModel,
                        optionLabel = { it.label },
                        onSelect = { m -> viewModel.update { it.copy(transcribeModel = m) } },
                    )
                    Text(
                        "ggml-${settings.transcribeModel.name.lowercase()}（離線轉錄；不附帶於 App，需下載）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (val state = modelState) {
                        is SettingsViewModel.ModelState.Absent -> {
                            Text(
                                "尚未下載 · 約 ${formatBytes(state.approxBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = requestModelDownload) { Text("下載模型") }
                        }
                        is SettingsViewModel.ModelState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "下載中… ${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is SettingsViewModel.ModelState.Present -> {
                            Text(
                                "已下載 · ${formatBytes(state.bytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { viewModel.deleteModel() }) { Text("刪除模型") }
                        }
                        is SettingsViewModel.ModelState.Failed -> {
                            Text(
                                "下載失敗：${state.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = requestModelDownload) { Text("重試下載") }
                        }
                    }
                } else {
                    Text(
                        "OpenAI 相容的 /audio/transcriptions（OpenAI、Groq、OpenRouter…）。金鑰只存在本機，不會內建於 App 或上傳。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextFieldSetting(
                        label = "API 位址（base URL）",
                        value = settings.cloud.baseUrl,
                        placeholder = "https://api.groq.com/openai/v1",
                        onValueChange = { v ->
                            viewModel.update { it.copy(cloud = it.cloud.copy(baseUrl = v.trim())) }
                        },
                    )
                    TextFieldSetting(
                        label = "模型名稱",
                        value = settings.cloud.model,
                        placeholder = "whisper-large-v3",
                        onValueChange = { v ->
                            viewModel.update { it.copy(cloud = it.cloud.copy(model = v.trim())) }
                        },
                    )
                    TextFieldSetting(
                        label = "API 金鑰",
                        value = settings.cloud.apiKey,
                        placeholder = "貼上你的金鑰",
                        isPassword = true,
                        onValueChange = { v ->
                            viewModel.update { it.copy(cloud = it.cloud.copy(apiKey = v.trim())) }
                        },
                    )
                    if (!settings.cloud.isConfigured) {
                        Text(
                            "填妥位址、模型與金鑰後才能使用雲端引擎。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Text(
                    "暫存檔為下載的音訊／字幕與分享進來的檔案複本，可安全清除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { viewModel.clearTempFiles() }) { Text("清除暫存檔") }
            }
        }
    }

    if (confirmMeteredDownload) {
        AlertDialog(
            onDismissRequest = { confirmMeteredDownload = false },
            title = { Text("使用行動數據下載？") },
            text = {
                Text("目前不是 Wi-Fi 連線，模型約 ${formatBytes(viewModel.selectedModelApproxBytes)}，仍要下載嗎？")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmMeteredDownload = false
                    viewModel.downloadModel()
                }) { Text("仍要下載") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMeteredDownload = false }) { Text("取消") }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
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
private fun TextFieldSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation =
            if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
    )
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
