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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.changyow.mediadler.core.transcribe.TranscriptFormatter
import com.changyow.mediadler.transcribe.TranscriptJob
import com.changyow.mediadler.transcribe.TranscriptStatus
import kotlin.math.roundToInt

/**
 * Result page for a transcription [job]. Streams live text + progress while RUNNING (with a 放棄
 * cancel), then the selectable transcript with copy / share, or an error.
 */
@Composable
fun TranscribeScreen(
    job: TranscriptJob?,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val running = job?.status == TranscriptStatus.QUEUED || job?.status == TranscriptStatus.RUNNING
    val text = job?.text.orEmpty()
    // job.text stays raw (the resume/merge seam dedup needs it newline-free); break it into readable
    // lines only here for display, copy and share.
    val displayText = TranscriptFormatter.format(text)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Surface fills edge-to-edge (background under the bars); content stays inside the
        // status/navigation bars. Without this the title overlaps the clock and the buttons the nav bar.
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)) {
            Text("轉文字", style = MaterialTheme.typography.titleLarge)
            Text(
                job?.label ?: "找不到項目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (running) {
                val progress = job?.progress ?: 0f
                // whisper decodes a window's audio in atomic 30 s chunks: nothing (no %, no text)
                // moves until the first chunk finishes, which on a slow device is a long, frozen-
                // looking wait. Until that first signal arrives, show an indeterminate bar so it
                // reads as "working", not stuck; switch to the real % once progress/text appear.
                val hasSignal = progress > 0.051f || text.isNotBlank()
                val total = job?.totalWindows ?: 0
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        job?.status == TranscriptStatus.QUEUED ->
                            Text("排隊中…", style = MaterialTheme.typography.bodyMedium)
                        !hasSignal ->
                            Text("處理中…（首段辨識需要一點時間）", style = MaterialTheme.typography.bodyMedium)
                        total > 1 -> Text(
                            "處理中… ${(progress * 100).roundToInt()}%（第 ${(job?.completedWindows ?: 0) + 1}/$total 段）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> Text(
                            "處理中… ${(progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (hasSignal) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) {
                when {
                    job?.status == TranscriptStatus.FAILED -> Text(
                        "轉文字失敗：${job.error ?: "未知錯誤"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    text.isNotBlank() -> SelectionContainer(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    ) {
                        Text(displayText, style = MaterialTheme.typography.bodyLarge)
                    }
                    job?.status == TranscriptStatus.COMPLETED -> Text(
                        "（沒有辨識到語音）",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onClose) { Text("關閉") }
                if (running) {
                    OutlinedButton(onClick = onCancel) { Text("放棄") }
                } else {
                    OutlinedButton(onClick = { copyToClipboard(context, displayText) }, enabled = text.isNotBlank()) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text("複製")
                    }
                    Button(onClick = { shareText(context, displayText) }, enabled = text.isNotBlank()) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Text("分享")
                    }
                }
            }
        }
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
