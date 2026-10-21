package xyz.yophon.gopan.core.notify

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.notifications.AppNotification
import xyz.yophon.gopan.core.data.notifications.NotificationLevel

/** 系统通知栏提醒「哪些是新的」 */
class InboxLogicTest {

    private fun n(id: String, at: String, read: Boolean = false, updatedAt: String = at) =
        AppNotification(id = id, kind = "job_failed", level = NotificationLevel.WARN, title = "t$id", body = "", createdAt = at, updatedAt = updatedAt, read = read)

    private val a = n("01a", "2026-10-21T10:00:00Z")
    private val b = n("01b", "2026-10-21T10:05:00Z")
    private val c = n("01c", "2026-10-21T10:10:00Z")

    @Test
    fun `first run records the newest without posting the backlog`() {
        val sel = InboxLogic.select(listOf(c, b, a), "u1", cursor = null)
        assertThat(sel.toPost).isEmpty()
        assertThat(sel.overflow).isEqualTo(0)
        assertThat(sel.cursor).isEqualTo(InboxCursor("u1", "01c", "2026-10-21T10:10:00Z"))
    }

    @Test
    fun `first run with nothing unread makes every later unread item new`() {
        val first = InboxLogic.select(emptyList(), "u1", cursor = null)
        assertThat(first.cursor.isEmpty).isTrue()
        val next = InboxLogic.select(listOf(b, a), "u1", first.cursor)
        assertThat(next.toPost.map { it.id }).containsExactly("01b", "01a").inOrder()
        assertThat(next.cursor.lastId).isEqualTo("01b")
    }

    @Test
    fun `only items newer than the cursor are posted, newest first`() {
        val cursor = InboxCursor("u1", "01a", "2026-10-21T10:00:00Z")
        val sel = InboxLogic.select(listOf(c, b, a), "u1", cursor)
        assertThat(sel.toPost.map { it.id }).containsExactly("01c", "01b").inOrder()
        assertThat(sel.cursor).isEqualTo(InboxCursor("u1", "01c", "2026-10-21T10:10:00Z"))
    }

    @Test
    fun `nothing new keeps the cursor`() {
        val cursor = InboxCursor("u1", "01c", "2026-10-21T10:10:00Z")
        val sel = InboxLogic.select(listOf(c, b), "u1", cursor)
        assertThat(sel.toPost).isEmpty()
        assertThat(sel.cursor).isEqualTo(cursor)
    }

    @Test
    fun `cursor never moves backwards when newer items were read meanwhile`() {
        // 游标停在 c;c 被读掉了,剩下的未读都更旧
        val cursor = InboxCursor("u1", "01c", "2026-10-21T10:10:00Z")
        val sel = InboxLogic.select(listOf(b, a), "u1", cursor)
        assertThat(sel.toPost).isEmpty()
        assertThat(sel.cursor).isEqualTo(cursor)
    }

    @Test
    fun `read items are never posted`() {
        val cursor = InboxCursor("u1", "01a", "2026-10-21T10:00:00Z")
        val sel = InboxLogic.select(listOf(c.copy(read = true), b), "u1", cursor)
        assertThat(sel.toPost.map { it.id }).containsExactly("01b")
        assertThat(sel.cursor.lastId).isEqualTo("01b")
    }

    @Test
    fun `merged updates of an old notification do not alert again`() {
        // a 在游标之前创建,之后又合并了几次(updatedAt 变新)
        val cursor = InboxCursor("u1", "01b", "2026-10-21T10:05:00Z")
        val merged = a.copy(count = 5, updatedAt = "2026-10-21T11:00:00Z")
        val sel = InboxLogic.select(listOf(merged, b), "u1", cursor)
        assertThat(sel.toPost).isEmpty()
    }

    @Test
    fun `same createdAt falls back to the id order`() {
        val same1 = n("01x", "2026-10-21T10:00:00Z")
        val same2 = n("01y", "2026-10-21T10:00:00Z")
        val cursor = InboxCursor("u1", "01x", "2026-10-21T10:00:00Z")
        val sel = InboxLogic.select(listOf(same2, same1), "u1", cursor)
        assertThat(sel.toPost.map { it.id }).containsExactly("01y")
    }

    @Test
    fun `timestamps with offsets compare as instants`() {
        // 10:30+08:00 = 02:30Z,比 10:00Z 早
        val earlier = n("01p", "2026-10-21T10:30:00+08:00")
        val cursor = InboxCursor("u1", "01a", "2026-10-21T10:00:00Z")
        assertThat(InboxLogic.select(listOf(earlier), "u1", cursor).toPost).isEmpty()
    }

    @Test
    fun `a cursor from another account is treated as a first run`() {
        val cursor = InboxCursor("someone-else", "01a", "2026-10-21T10:00:00Z")
        val sel = InboxLogic.select(listOf(c, b), "u1", cursor)
        assertThat(sel.toPost).isEmpty()
        assertThat(sel.cursor).isEqualTo(InboxCursor("u1", "01c", "2026-10-21T10:10:00Z"))
    }

    @Test
    fun `more than the limit posts the newest and counts the rest`() {
        val items = (1..8).map { i -> n("02$i", "2026-10-21T12:0$i:00Z") }.reversed()
        val cursor = InboxCursor("u1", "01c", "2026-10-21T10:10:00Z")
        val sel = InboxLogic.select(items, "u1", cursor)
        assertThat(sel.toPost).hasSize(InboxLogic.MAX_POSTED)
        assertThat(sel.toPost.first().id).isEqualTo("028")
        assertThat(sel.overflow).isEqualTo(8 - InboxLogic.MAX_POSTED)
        assertThat(sel.cursor.lastId).isEqualTo("028")
    }
}
