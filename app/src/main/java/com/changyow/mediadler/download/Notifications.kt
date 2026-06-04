package com.changyow.mediadler.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.changyow.mediadler.R

object Notifications {
    const val CHANNEL_PROGRESS = "downloads_progress"
    const val CHANNEL_STATUS = "downloads_status"
    const val CHANNEL_TX_PROGRESS = "transcribe_progress"
    const val CHANNEL_TX_STATUS = "transcribe_status"
    const val SUMMARY_ID = 1
    // Far above the download per-job counter (which starts at SUMMARY_ID+1 and climbs) so the two
    // services' notification IDs can never collide within a session.
    const val TX_SUMMARY_ID = 100_000

    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "下載進度", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "下載狀態", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TX_PROGRESS, "轉錄進度", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TX_STATUS, "轉錄狀態", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun txSummary(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_TX_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("media-dler")
            .setContentText("轉文字中…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    fun txProgress(context: Context, id: Int, title: String, progress: Float, open: PendingIntent?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_TX_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (progress in 0f..1f) {
            builder.setContentText("處理中… ${(progress * 100).toInt()}%")
            builder.setProgress(100, (progress * 100).toInt(), false)
        } else {
            builder.setContentText("處理中…")
            builder.setProgress(0, 0, true)
        }
        open?.let { builder.setContentIntent(it) }
        notify(context, id, builder.build())
    }

    fun txCompleted(context: Context, id: Int, title: String, open: PendingIntent?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_TX_STATUS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText("轉文字完成，點擊查看")
            .setAutoCancel(true)
        open?.let { builder.setContentIntent(it) }
        notify(context, id, builder.build())
    }

    fun txFailed(context: Context, id: Int, title: String, message: String?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_TX_STATUS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText("轉文字失敗：${message ?: "未知錯誤"}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("轉文字失敗：${message ?: "未知錯誤"}"))
            .setAutoCancel(true)
        notify(context, id, builder.build())
    }

    fun summary(context: Context, active: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("media-dler")
            .setContentText(if (active > 0) "下載中（$active）" else "等待中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    fun progress(context: Context, id: Int, title: String, progress: Float) {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (progress in 0f..1f) {
            builder.setContentText("${(progress * 100).toInt()}%")
            builder.setProgress(100, (progress * 100).toInt(), false)
        } else {
            builder.setContentText("處理中…")
            builder.setProgress(0, 0, true)
        }
        notify(context, id, builder.build())
    }

    fun completed(context: Context, id: Int, title: String, uri: String, mime: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText("下載完成")
            .setAutoCancel(true)
        openIntent(context, uri, mime)?.let { builder.setContentIntent(it) }
        notify(context, id, builder.build())
    }

    fun failed(context: Context, id: Int, title: String, message: String?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText("下載失敗：${message ?: "未知錯誤"}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("下載失敗：${message ?: "未知錯誤"}"))
            .setAutoCancel(true)
        notify(context, id, builder.build())
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService<NotificationManager>()?.cancel(id)
    }

    private fun openIntent(context: Context, uri: String, mime: String): PendingIntent? = runCatching {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PendingIntent.getActivity(
            context,
            uri.hashCode(),
            view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }.getOrNull()

    private fun notify(context: Context, id: Int, notification: Notification) {
        // On API 33+ this silently no-ops if POST_NOTIFICATIONS is not granted.
        context.getSystemService<NotificationManager>()?.notify(id, notification)
    }
}
