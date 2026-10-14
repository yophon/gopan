package xyz.yophon.gopan.core.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.ui.format.formatBytes
import javax.inject.Inject
import javax.inject.Singleton

/** App 是否在前台(MainActivity onStart/onStop 维护):前台时不发"传输完成"通知,界面自己会显示 */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}

/**
 * 传输通知:进行中(前台服务的常驻通知,低优先级)与结果(完成 / 失败)。
 * 点按都回到 App 的传输列表;单个文件下载完成的通知点按直接打开文件。
 */
@Singleton
class TransferNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val opener: FileOpener,
) {
    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.transfer_channel_progress), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.transfer_channel_progress_desc)
                    setShowBadge(false)
                },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, context.getString(R.string.transfer_channel_result), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.transfer_channel_result_desc) },
        )
    }

    fun foregroundInfo(summary: TransferSummary): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openTransfersIntent())
            .apply {
                if (summary.active == 0) {
                    setContentTitle(context.getString(R.string.transfer_notif_starting))
                    setProgress(0, 0, true)
                } else {
                    setContentTitle(summary.singleName ?: context.getString(R.string.transfer_notif_title_many, summary.active))
                    setContentText(progressText(summary))
                    if (summary.totalBytes > 0 && !summary.waitingNetwork) {
                        val pct = (summary.doneBytes * 100 / summary.totalBytes).toInt().coerceIn(0, 100)
                        setProgress(100, pct, false)
                    } else {
                        setProgress(0, 0, true)
                    }
                }
            }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_PROGRESS, notification)
        }
    }

    private fun progressText(s: TransferSummary): String {
        if (s.waitingNetwork) return context.getString(R.string.transfer_notif_waiting_network)
        val parts = buildList {
            if (s.uploads > 0) add(context.getString(R.string.transfer_notif_uploads, s.uploads))
            if (s.downloads > 0) add(context.getString(R.string.transfer_notif_downloads, s.downloads))
            if (s.totalBytes > 0) add("${formatBytes(s.doneBytes)} / ${formatBytes(s.totalBytes)}")
        }
        return parts.joinToString(" · ")
    }

    /** 一轮结束:App 在后台时发结果通知 */
    fun postResult(report: DrainReport) {
        if (AppVisibility.foreground || !canPost()) return
        ensureChannels()
        val nm = NotificationManagerCompat.from(context)
        if (report.uploadsDone > 0 || report.uploadsFailed > 0) {
            val title = when {
                report.uploadsFailed == 0 -> context.getString(R.string.transfer_notif_uploaded, report.uploadsDone)
                report.uploadsDone == 0 -> context.getString(R.string.transfer_notif_upload_failed, report.uploadsFailed)
                else -> context.getString(R.string.transfer_notif_uploaded, report.uploadsDone) + "," +
                    context.getString(R.string.transfer_notif_upload_failed, report.uploadsFailed)
            }
            notifySafely(nm, ID_UPLOAD_RESULT, result(title, context.getString(R.string.transfer_notif_tap_list), openTransfersIntent()))
        }
        val downloaded = report.downloadsDone
        if (downloaded.isNotEmpty() || report.downloadsFailed > 0) {
            val single = downloaded.singleOrNull()?.takeIf { report.downloadsFailed == 0 && it.finalUri != null }
            val (title, text, intent) = if (single != null) {
                Triple(
                    context.getString(R.string.transfer_notif_downloaded_one, single.name),
                    context.getString(R.string.transfer_notif_tap_open),
                    opener.viewIntent(single)?.let {
                        PendingIntent.getActivity(context, single.id.toInt(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    } ?: openTransfersIntent(),
                )
            } else {
                val t = buildList {
                    if (downloaded.isNotEmpty()) add(context.getString(R.string.transfer_notif_downloaded_many, downloaded.size))
                    if (report.downloadsFailed > 0) add(context.getString(R.string.transfer_notif_download_failed, report.downloadsFailed))
                }.joinToString(",")
                Triple(t, context.getString(R.string.transfer_notif_tap_list), openTransfersIntent())
            }
            notifySafely(nm, ID_DOWNLOAD_RESULT, result(title, text, intent))
        }
    }

    private fun result(title: String, text: String, intent: PendingIntent) =
        NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifySafely(nm: NotificationManagerCompat, id: Int, n: android.app.Notification) {
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // 通知权限在这期间被收回
        }
    }

    private fun openTransfersIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TRANSFERS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_TRANSFERS, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_PROGRESS = "transfer_progress"
        const val CHANNEL_RESULT = "transfer_result"
        const val ID_PROGRESS = 1001
        const val ID_UPLOAD_RESULT = 1002
        const val ID_DOWNLOAD_RESULT = 1003
        private const val REQUEST_TRANSFERS = 10
    }
}
