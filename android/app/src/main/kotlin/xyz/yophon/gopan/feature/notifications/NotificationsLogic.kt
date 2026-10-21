package xyz.yophon.gopan.feature.notifications

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.core.data.notifications.AppNotification
import xyz.yophon.gopan.core.data.notifications.NotificationTarget
import xyz.yophon.gopan.core.data.notifications.NotificationTargetKind
import xyz.yophon.gopan.core.ui.format.parseTime
import xyz.yophon.gopan.feature.chat.ChatLogic
import java.time.LocalDate
import java.time.ZoneId

/** 点开一条通知去哪(App 里的页面)。与服务端 TargetPath(网页路径)同一套解析,见 doc/22 三.4 */
sealed interface NotificationNav {
    /** 文件页打开 [folderId](null = 根目录);[previewId] 非空时再打开它的预览 */
    data class Drive(val folderId: String?, val previewId: String? = null) : NotificationNav

    /** 「分享」tab 的「收集」标签 */
    data class FileRequests(val requestId: String?) : NotificationNav

    /** 「分享」tab 的「直链」标签,有 [linkId] 时打开那一条 */
    data class Links(val linkId: String?) : NotificationNav

    /** 传输列表的「后台任务」栏 */
    data object Jobs : NotificationNav

    /** 登录设备 */
    data object Sessions : NotificationNav

    /** WebDAV 应用密码 */
    data object AppPasswords : NotificationNav

    /** Agent 接入(MCP API Key、OAuth 授权) */
    data object Agent : NotificationNav

    /** 「我的」(图床密钥在网页上管,App 里没有对应页面) */
    data object Account : NotificationNav

    data object Admin : NotificationNav
}

/** 列表的一行:日期分隔 / 一条通知 */
@Immutable
sealed interface NotificationRow {
    val key: String

    data class DayHeader(val label: String, val day: LocalDate) : NotificationRow {
        override val key: String get() = "day-$day"
    }

    data class Item(val notification: AppNotification) : NotificationRow {
        override val key: String get() = "n-${notification.id}"
    }
}

/** 通知页的纯函数(单测覆盖):按天分组、跳转目标 */
object NotificationsLogic {

    /**
     * 跳转目标。NODE 先给「所在文件夹 + 预览这个节点」,打开前再按节点类型细化(见 [refineNodeNav]);
     * CREDENTIALS 按种类去对应的页面;ADMIN 只对管理员;没有目标或认不出的返回 null(只标已读)。
     */
    fun navFor(target: NotificationTarget?, isAdmin: Boolean): NotificationNav? {
        target ?: return null
        return when (target.kind) {
            NotificationTargetKind.NODE -> NotificationNav.Drive(folderId = target.parentId, previewId = target.id)
            NotificationTargetKind.FILE_REQUEST -> NotificationNav.FileRequests(target.id)
            NotificationTargetKind.LINK -> NotificationNav.Links(target.id)
            NotificationTargetKind.JOBS -> NotificationNav.Jobs
            NotificationTargetKind.SESSIONS -> NotificationNav.Sessions
            NotificationTargetKind.CREDENTIALS -> when (target.id) {
                CREDENTIAL_WEBDAV -> NotificationNav.AppPasswords
                CREDENTIAL_MCP -> NotificationNav.Agent
                // 图床密钥(img)与认不出的:App 里没有管理页面,回「我的」
                else -> NotificationNav.Account
            }
            NotificationTargetKind.ADMIN -> if (isAdmin) NotificationNav.Admin else null
        }
    }

    /**
     * NODE 目标拿到节点类型之后:文件夹直接进它自己(解压出来的、离线下载的文件夹),文件进所在文件夹再预览;
     * [isFolder] 为 null(节点查不到、断网)时只进所在文件夹。
     */
    fun refineNodeNav(nav: NotificationNav.Drive, isFolder: Boolean?): NotificationNav.Drive = when {
        nav.previewId == null -> nav
        isFolder == true -> NotificationNav.Drive(folderId = nav.previewId)
        isFolder == false -> nav
        else -> NotificationNav.Drive(folderId = nav.folderId)
    }

    /** 按创建日期分组(新的在前,服务端已排好);日期标题与传输助手一致:今天 / 昨天 / M月D日 / yyyy-MM-dd */
    fun rows(items: List<AppNotification>, zone: ZoneId, today: LocalDate): List<NotificationRow> {
        val out = ArrayList<NotificationRow>(items.size + 4)
        var current: LocalDate? = null
        for (n in items) {
            val day = parseTime(n.createdAt)?.atZone(zone)?.toLocalDate() ?: current ?: today
            if (day != current) {
                current = day
                out += NotificationRow.DayHeader(ChatLogic.dayLabel(day, today), day)
            }
            out += NotificationRow.Item(n)
        }
        return out
    }

    /** 角标文字:超过 99 显示 99+ */
    fun badgeText(count: Int): String = if (count > 99) "99+" else count.toString()

    const val CREDENTIAL_WEBDAV = "webdav"
    const val CREDENTIAL_MCP = "mcp"
    const val CREDENTIAL_IMG = "img"
}
