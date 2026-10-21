package xyz.yophon.gopan.core.notify

import xyz.yophon.gopan.core.data.notifications.AppNotification
import xyz.yophon.gopan.core.ui.format.parseTime

/**
 * 系统通知栏提醒「已经提醒到哪条」:这个账号见过的最新一条未读通知。
 * [lastId] 与 [lastCreatedAt] 都为空 = 第一次检查时一条未读都没有(之后出现的未读全都是新的)。
 */
data class InboxCursor(val userId: String, val lastId: String, val lastCreatedAt: String) {
    val isEmpty: Boolean get() = lastId.isEmpty() && lastCreatedAt.isEmpty()
}

/** 一次检查的结论:要发的系统通知(新的在前,最多 [InboxLogic.MAX_POSTED] 条)、没发出来的条数、新的游标 */
data class InboxSelection(val toPost: List<AppNotification>, val overflow: Int, val cursor: InboxCursor)

/**
 * 「哪些是新的」(纯函数,单测覆盖)。按创建时间比(同一时刻再比 id),不看 updatedAt:
 * 合并(同一件事 30 分钟内又发生)只更新已有那条,服务端也只推第一次,这里同样不再提醒。
 */
object InboxLogic {
    /** 一次最多单独发几条;更多的合成一条「另有 N 条新通知」 */
    const val MAX_POSTED = 5

    /**
     * [items] 是 notifications(unreadOnly: true) 的第一页。[cursor] 为 null(第一次)或属于别的账号时
     * 只记下最新的一条、不补发积压的;之后只发比游标新、仍未读的。游标只进不退。
     */
    fun select(items: List<AppNotification>, userId: String, cursor: InboxCursor?): InboxSelection {
        val unread = items.filter { !it.read }
        val newest = unread.maxWithOrNull(::compareNotifications)
        if (cursor == null || cursor.userId != userId) {
            val first = newest?.let { InboxCursor(userId, it.id, it.createdAt) } ?: InboxCursor(userId, "", "")
            return InboxSelection(emptyList(), 0, first)
        }
        val fresh = unread.filter { cursor.isEmpty || compare(it.createdAt, it.id, cursor.lastCreatedAt, cursor.lastId) > 0 }
            .sortedWith { a, b -> compareNotifications(b, a) }
        val next = if (newest != null && (cursor.isEmpty || compare(newest.createdAt, newest.id, cursor.lastCreatedAt, cursor.lastId) > 0)) {
            InboxCursor(userId, newest.id, newest.createdAt)
        } else {
            cursor
        }
        val posted = fresh.take(MAX_POSTED)
        return InboxSelection(posted, fresh.size - posted.size, next)
    }

    private fun compareNotifications(a: AppNotification, b: AppNotification): Int = compare(a.createdAt, a.id, b.createdAt, b.id)

    /** 先比创建时间;解析不了或同一时刻时比 id(服务端 id 是时间有序的 UUIDv7) */
    internal fun compare(aAt: String, aId: String, bAt: String, bId: String): Int {
        val ta = parseTime(aAt)
        val tb = parseTime(bAt)
        if (ta != null && tb != null) {
            val c = ta.compareTo(tb)
            if (c != 0) return c
        }
        return aId.compareTo(bId)
    }
}
