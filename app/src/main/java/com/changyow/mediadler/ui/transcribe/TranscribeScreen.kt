package com.changyow.mediadler.ui.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.changyow.mediadler.core.transcribe.TranscriptFormatter
import com.changyow.mediadler.transcribe.TranscriptJob
import com.changyow.mediadler.transcribe.TranscriptStatus
import kotlin.math.roundToInt

/**
 * Result page for a transcription [job]. Streams live text + progress while RUNNING (with a 放棄
 * cancel), then the selectable transcript with copy / share, or an error.
 */
@OptIn(ExperimentalLayoutApi::class)
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
        Box(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("轉文字", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            job?.label ?: "找不到項目",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        job?.method?.let { method ->
                            Text(
                                "轉譯方式：$method",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    if (running) {
                        val progress = job?.progress ?: 0f
                        // Before the first decoded chunk, indeterminate progress communicates
                        // activity without inventing a percentage or promising an ETA.
                        val hasSignal = progress > 0.051f || text.isNotBlank()
                        val total = job?.totalWindows ?: 0
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = if (job?.status == TranscriptStatus.FAILED) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                if (job?.status == TranscriptStatus.FAILED) "轉錄失敗" else "逐字稿",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (job?.status == TranscriptStatus.FAILED) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            when {
                                job?.status == TranscriptStatus.FAILED -> SelectionContainer {
                                    Text(
                                        "轉文字失敗：${job.error ?: "未知錯誤"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                                text.isNotBlank() -> SelectionContainer {
                                    Text(displayText, style = MaterialTheme.typography.bodyLarge)
                                }
                                job?.status == TranscriptStatus.COMPLETED -> Text(
                                    "（沒有辨識到語音）",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                job?.status == TranscriptStatus.CANCELLED -> Text(
                                    "已放棄轉錄",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                running -> Text(
                                    "辨識出的文字會顯示在這裡。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> Text(
                                    "找不到這筆轉錄記錄。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("關閉") }
                    if (running) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("放棄") }
                    } else {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, displayText) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("複製")
                        }
                        Button(
                            onClick = { shareText(context, displayText) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("分享")
                        }
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
