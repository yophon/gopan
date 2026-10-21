package xyz.yophon.gopan.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.notifications.AppNotification
import xyz.yophon.gopan.core.data.notifications.NotificationLevel
import xyz.yophon.gopan.core.ui.format.parseTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 站内通知 → 系统通知栏(doc/22 三.4、决策 6)。两个渠道:普通(提示音、不弹出)与严重(ALERT,弹出横幅)。
 * 每条一个 tag(通知 id),点按都进 App 的通知列表;进了通知列表就把已发的收起来。
 */
@Singleton
class InboxNotifier @Inject constructor(@ApplicationContext private val context: Context) : InboxPoster {

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INBOX, context.getString(R.string.inbox_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.inbox_channel_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, context.getString(R.string.inbox_channel_alert), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.inbox_channel_alert_desc) },
        )
    }

    override fun canPost(): Boolean =
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** [items] 新的在前;[overflow] 条没单独发的合成一条「另有 N 条」 */
    override fun post(items: List<AppNotification>, overflow: Int) {
        if (items.isEmpty() || !canPost()) return
        ensureChannels()
        val nm = NotificationManagerCompat.from(context)
        // 旧的先发,新的排在通知栏最上面
        items.asReversed().forEach { n ->
            val title = if (n.count > 1) context.getString(R.string.inbox_notif_title_count, n.title, n.count) else n.title
            val builder = NotificationCompat.Builder(context, if (n.level == NotificationLevel.ALERT) CHANNEL_ALERT else CHANNEL_INBOX)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(n.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setCategory(if (n.level == NotificationLevel.ALERT) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(openListIntent())
            parseTime(n.createdAt)?.let { builder.setWhen(it.toEpochMilli()).setShowWhen(true) }
            notifySafely(nm, TAG_PREFIX + n.id, builder.build())
        }
        if (overflow > 0) {
            val more = NotificationCompat.Builder(context, CHANNEL_INBOX)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(context.getString(R.string.inbox_notif_more, overflow))
                .setContentText(context.getString(R.string.inbox_notif_tap_list))
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setContentIntent(openListIntent())
                .build()
            notifySafely(nm, TAG_MORE, more)
        }
    }

    /** 打开了通知列表:通知栏里已发的站内通知都收起来 */
    override fun clearPosted() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true || it.tag == TAG_MORE }.forEach { nm.cancel(it.tag, it.id) }
        } catch (e: RuntimeException) {
            // 个别 ROM 上 getActiveNotifications 会抛;收不起来不影响什么
        }
    }

    private fun notifySafely(nm: NotificationManagerCompat, tag: String, n: android.app.Notification) {
        try {
            nm.notify(tag, ID, n)
        } catch (e: SecurityException) {
            // 通知权限在这期间被收回
        }
    }

    private fun openListIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_NOTIFICATIONS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_LIST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_INBOX = "inbox"
        const val CHANNEL_ALERT = "inbox_alert"
        private const val GROUP = "xyz.yophon.gopan.INBOX"
        private const val TAG_PREFIX = "inbox:"
        private const val TAG_MORE = "inbox-more"
        private const val ID = 2001
        private const val REQUEST_LIST = 20
    }
}
