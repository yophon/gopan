package xyz.yophon.gopan.feature.shortcut

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node

/**
 * 桌面入口:长按 App 图标出现的「上传文件」「传输助手」「搜索」,以及文件夹「添加到桌面」。
 * 长按的几项在启动时用代码发布(不写静态 shortcuts.xml:那里要写死包名,开发版带后缀的包名就对不上)。
 */
object AppShortcuts {

    fun publish(context: Context) {
        val list = listOf(
            ShortcutInfoCompat.Builder(context, "upload")
                .setShortLabel(context.getString(R.string.shortcut_upload))
                .setLongLabel(context.getString(R.string.shortcut_upload_long))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_upload))
                .setIntent(Intent(context, QuickActionActivity::class.java).setAction(QuickActionActivity.ACTION_UPLOAD))
                .setRank(0)
                .build(),
            ShortcutInfoCompat.Builder(context, "chat")
                .setShortLabel(context.getString(R.string.shortcut_chat))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_chat))
                .setIntent(open(context, MainActivity.OPEN_CHAT))
                .setRank(1)
                .build(),
            ShortcutInfoCompat.Builder(context, "search")
                .setShortLabel(context.getString(R.string.shortcut_search))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_search))
                .setIntent(open(context, MainActivity.OPEN_SEARCH))
                .setRank(2)
                .build(),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, list) }
    }

    fun canPinFolder(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** 文件夹「添加到桌面」:桌面上多一个图标,点了直接进这个文件夹。返回 false = 桌面不支持 */
    fun pinFolder(context: Context, folder: Node): Boolean {
        if (!canPinFolder(context)) return false
        val info = ShortcutInfoCompat.Builder(context, "folder-${folder.id}")
            .setShortLabel(folder.name.take(SHORT_LABEL_MAX))
            .setLongLabel(folder.name)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_folder))
            .setIntent(open(context, MainActivity.OPEN_FOLDER).putExtra(MainActivity.EXTRA_NODE_ID, folder.id))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** 打开主界面的某一处(MainActivity 按 EXTRA_OPEN 跳转) */
    fun open(context: Context, target: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_OPEN, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private const val SHORT_LABEL_MAX = 24
}
