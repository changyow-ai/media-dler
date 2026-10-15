package com.changyow.mediadler.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Local-file actions retain the same callbacks as the share receiver. */
@Composable
fun LocalMediaSheet(
    isVideo: Boolean,
    onText: () -> Unit,
    onAudio: () -> Unit,
    onClose: () -> Unit,
) {
    PickerDialog(onClose = onClose) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PickerHeader(
                title = if (isVideo) "處理這部影片" else "處理這個聲音檔",
                subtitle = "選擇接下來要進行的操作。",
                onClose = onClose,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (isVideo) Icons.Filled.Videocam else Icons.Filled.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        if (isVideo) "將影片中的語音轉成文字，或只儲存音訊。" else "將聲音檔中的語音轉成文字。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onText, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("轉成文字")
                }
                if (isVideo) {
                    OutlinedButton(onClick = onAudio, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("取出聲音（存成音檔）")
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("取消")
                }
            }
        }
    }
}
