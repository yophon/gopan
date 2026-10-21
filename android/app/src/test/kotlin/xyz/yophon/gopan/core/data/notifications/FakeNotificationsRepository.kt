package xyz.yophon.gopan.core.data.notifications

import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

/**
 * 内存版通知仓库:[items] 新的在前,按 [pageSize] 分页(游标是下一页的起始下标)。
 * [failNext] 置上时下一次调用抛 NETWORK;[calls] 记下每次调用。
 */
class FakeNotificationsRepository : NotificationsRepository {
    var items: List<AppNotification> = emptyList()
    var pageSize = 30
    var failNext = false
    val calls = mutableListOf<String>()

    private fun maybeFail() {
        if (failNext) {
            failNext = false
            throw GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        }
    }

    override suspend fun list(cursor: String?, unreadOnly: Boolean): NotificationPage {
        calls += "list($cursor,$unreadOnly)"
        maybeFail()
        val all = if (unreadOnly) items.filter { !it.read } else items
        val start = cursor?.toInt() ?: 0
        val end = minOf(all.size, start + pageSize)
        return NotificationPage(all.subList(start, end), if (end < all.size) end.toString() else null)
    }

    override suspend fun unreadCount(): Int {
        calls += "unreadCount"
        maybeFail()
        return items.count { !it.read }
    }

    override suspend fun markRead(ids: List<String>?): Int {
        calls += "markRead(${ids?.joinToString(",")})"
        maybeFail()
        var n = 0
        items = items.map { if (!it.read && (ids == null || it.id in ids)) it.copy(read = true).also { n++ } else it }
        return n
    }

    override suspend fun clear(): Int {
        calls += "clear"
        maybeFail()
        val n = items.size
        items = emptyList()
        return n
    }

    companion object {
        fun notification(
            id: String,
            createdAt: String = "2026-10-21T10:00:00Z",
            read: Boolean = false,
            target: NotificationTarget? = null,
            level: NotificationLevel = NotificationLevel.INFO,
            count: Int = 1,
        ) = AppNotification(
            id = id,
            kind = "job_done",
            level = level,
            title = "标题 $id",
            body = "正文 $id",
            count = count,
            target = target,
            createdAt = createdAt,
            read = read,
        )
    }
}
