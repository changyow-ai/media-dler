package com.changyow.mediadler.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Chooser shown when a local media file is shared in. A video offers 轉成文字 (transcribe) or
 * 取出聲音 (extract its audio track to a saved .m4a); an audio file offers only 轉成文字. Mirrors
 * [ShareSheet]'s Dialog + Surface styling.
 */
@Composable
fun LocalMediaSheet(
    isVideo: Boolean,
    onText: () -> Unit,
    onAudio: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (isVideo) "要怎麼處理這部影片？" else "要怎麼處理這個聲音檔？",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Button(onClick = onText, modifier = Modifier.fillMaxWidth()) {
                        Text("轉成文字")
                    }
                    if (isVideo) {
                        OutlinedButton(onClick = onAudio, modifier = Modifier.fillMaxWidth()) {
                            Text("取出聲音（存成音檔）")
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = onClose) { Text("取消") }
                    }
                }
            }
        }
    }
}
