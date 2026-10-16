package com.changyow.mediadler.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadRateSetting(value: Long?, onChange: (Long?) -> Unit) {
    var showCustom by rememberSaveable { mutableStateOf(false) }
    val custom = value !in downloadRatePresets
    Text(formatDownloadRate(value), style = MaterialTheme.typography.headlineSmall)
    Text(
        if (custom) "每次下載的速度上限 · 自訂" else "每次下載的速度上限",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        downloadRatePresets.forEach { rate ->
            RateChip(label = formatDownloadRate(rate), selected = rate == value, onClick = { onChange(rate) })
        }
    }
    TextButton(onClick = { showCustom = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("自訂速限")
    }
    Text(
        "套用至之後開始或手動重試的下載。正在下載的項目維持原速限。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (showCustom) {
        CustomDownloadRateDialog(
            current = value,
            onDismiss = { showCustom = false },
            onSave = { rate ->
                onChange(rate)
                showCustom = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomDownloadRateDialog(current: Long?, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var unit by rememberSaveable { mutableStateOf(current?.let(::downloadRateUnit) ?: DownloadRateUnit.MB) }
    var amount by rememberSaveable { mutableStateOf(current?.let { downloadRateAmount(it, unit) } ?: "2") }
    val rate = remember(amount, unit) { parseDownloadRate(amount, unit) }
    val invalid = amount.isNotBlank() && rate == null
    val save = { if (rate != null) onSave(rate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自訂下載速限") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("輸入下載速度上限，並選擇單位。")
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("速度上限") },
                    suffix = { Text(unit.label) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = {
                        Text(if (invalid) "請輸入大於 0 的有效數字，最小單位為 1 B/s。" else "可輸入小數，例如 1.5。")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DownloadRateUnit.entries.forEach { option ->
                        RateChip(label = option.label, selected = option == unit, onClick = { unit = option })
                    }
                }
                Text(
                    "1 MB/s = 1,000 KB/s；此設定適用於媒體下載任務。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = save, enabled = rate != null) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
    )
}
