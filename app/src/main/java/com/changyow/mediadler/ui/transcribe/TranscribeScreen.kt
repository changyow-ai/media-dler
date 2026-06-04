package com.changyow.mediadler.ui.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Result page for a transcription. Renders the [TranscribeViewModel.UiState]: progress while the
 * engine runs, then the selectable text with copy / share, or an error.
 */
@Composable
fun TranscribeScreen(
    sourceLabel: String,
    state: TranscribeViewModel.UiState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("轉文字", style = MaterialTheme.typography.titleLarge)
            Text(
                sourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) {
                when (state) {
                    is TranscribeViewModel.UiState.Running -> RunningBody(state.progress)
                    is TranscribeViewModel.UiState.Error -> Text(
                        "轉錄失敗：${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is TranscribeViewModel.UiState.Done -> SelectionContainer(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            state.text.ifBlank { "（沒有辨識到語音）" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            val done = state as? TranscribeViewModel.UiState.Done
            val text = done?.text.orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onClose) { Text("關閉") }
                OutlinedButton(onClick = { copyToClipboard(context, text) }, enabled = text.isNotBlank()) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text("複製")
                }
                Button(onClick = { shareText(context, text) }, enabled = text.isNotBlank()) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("分享")
                }
            }
        }
    }
}

@Composable
private fun RunningBody(progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("轉錄中… ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("transcript", text))
    Toast.makeText(context, "已複製", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "分享逐字稿"))
}
