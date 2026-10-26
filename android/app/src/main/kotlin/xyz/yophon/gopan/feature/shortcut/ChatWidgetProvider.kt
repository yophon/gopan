package xyz.yophon.gopan.feature.shortcut

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.R

/**
 * 桌面小组件「传输助手」:一条墨黑的胶囊,点左边打开传输助手,右边两个按钮直接选照片 / 文件发过去。
 * 没有要刷新的内容(不显示消息),只在放上桌面时画一次。
 */
class ChatWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun views(context: Context): RemoteViews = RemoteViews(context.packageName, R.layout.widget_chat).apply {
        setOnClickPendingIntent(R.id.widget_open, activity(context, REQ_OPEN, AppShortcuts.open(context, MainActivity.OPEN_CHAT)))
        setOnClickPendingIntent(R.id.widget_photo, activity(context, REQ_PHOTO, quick(context, QuickActionActivity.ACTION_SEND_MEDIA)))
        setOnClickPendingIntent(R.id.widget_file, activity(context, REQ_FILE, quick(context, QuickActionActivity.ACTION_SEND_FILES)))
    }

    private fun quick(context: Context, action: String): Intent =
        Intent(context, QuickActionActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun activity(context: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private companion object {
        const val REQ_OPEN = 7101
        const val REQ_PHOTO = 7102
        const val REQ_FILE = 7103
    }
}
