package xyz.yophon.gopan.feature.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.notifications.FakeNotificationsRepository.Companion.notification
import xyz.yophon.gopan.core.data.notifications.NotificationTarget
import xyz.yophon.gopan.core.data.notifications.NotificationTargetKind
import java.time.LocalDate
import java.time.ZoneId

/** 通知点开去哪、按天分组 */
class NotificationsLogicTest {

    private fun t(kind: NotificationTargetKind, id: String? = null, parentId: String? = null) = NotificationTarget(kind, id, parentId)

    @Test
    fun `node opens the containing folder and previews the node`() {
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.NODE, "f1", "dir"), isAdmin = false))
            .isEqualTo(NotificationNav.Drive(folderId = "dir", previewId = "f1"))
        // 在根目录
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.NODE, "f1", null), isAdmin = false))
            .isEqualTo(NotificationNav.Drive(folderId = null, previewId = "f1"))
    }

    @Test
    fun `node nav is refined by the node kind`() {
        val nav = NotificationNav.Drive(folderId = "dir", previewId = "x")
        // 文件:所在文件夹 + 预览
        assertThat(NotificationsLogic.refineNodeNav(nav, isFolder = false)).isEqualTo(nav)
        // 文件夹(解压、离线下载的结果):直接进它
        assertThat(NotificationsLogic.refineNodeNav(nav, isFolder = true)).isEqualTo(NotificationNav.Drive(folderId = "x"))
        // 查不到类型:只进所在文件夹
        assertThat(NotificationsLogic.refineNodeNav(nav, isFolder = null)).isEqualTo(NotificationNav.Drive(folderId = "dir"))
        // 没有节点 id 的原样返回
        val bare = NotificationNav.Drive(folderId = "dir")
        assertThat(NotificationsLogic.refineNodeNav(bare, isFolder = true)).isEqualTo(bare)
    }

    @Test
    fun `shares, jobs and sessions targets`() {
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.FILE_REQUEST, "r1"), false)).isEqualTo(NotificationNav.FileRequests("r1"))
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.LINK, "l1"), false)).isEqualTo(NotificationNav.Links("l1"))
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.JOBS), false)).isEqualTo(NotificationNav.Jobs)
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.SESSIONS), false)).isEqualTo(NotificationNav.Sessions)
    }

    @Test
    fun `credentials go to the screen that manages that kind`() {
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.CREDENTIALS, "webdav"), false)).isEqualTo(NotificationNav.AppPasswords)
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.CREDENTIALS, "mcp"), false)).isEqualTo(NotificationNav.Agent)
        // 图床密钥只在网页上管;认不出的种类同样回「我的」
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.CREDENTIALS, "img"), false)).isEqualTo(NotificationNav.Account)
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.CREDENTIALS, null), false)).isEqualTo(NotificationNav.Account)
    }

    @Test
    fun `activity target opens the activity list filtered by source and time`() {
        // 大量删除提醒:id = 来源(kind:ref),parentId = 从什么时候起
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.ACTIVITY, "webdav:ap-9", "2026-10-21T00:00:00Z"), false))
            .isEqualTo(NotificationNav.Activity(actor = "webdav:ap-9", since = "2026-10-21T00:00:00Z"))
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.ACTIVITY), false)).isEqualTo(NotificationNav.Activity())
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.ACTIVITY, " "), false)).isEqualTo(NotificationNav.Activity())
        // 筛选参数与动态页的解析对得上
        val nav = NotificationsLogic.navFor(t(NotificationTargetKind.ACTIVITY, "agent:oauth:g1", "2026-10-21T00:00:00Z"), false)
            as NotificationNav.Activity
        val query = xyz.yophon.gopan.feature.activity.ActivityLogic.queryFor(nav.actor, nav.since)
        assertThat(query.actorKind).isEqualTo(xyz.yophon.gopan.core.data.activity.ActorKind.AGENT)
        assertThat(query.actorRef).isEqualTo("oauth:g1")
        assertThat(query.since).isEqualTo("2026-10-21T00:00:00Z")
    }

    @Test
    fun `feed target goes to the feed`() {
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.FEED, "f1"), false)).isEqualTo(NotificationNav.Feed("f1"))
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.FEED), false)).isEqualTo(NotificationNav.Feed(null))
    }

    @Test
    fun `admin target only for admins, no target goes nowhere`() {
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.ADMIN), isAdmin = true)).isEqualTo(NotificationNav.Admin)
        assertThat(NotificationsLogic.navFor(t(NotificationTargetKind.ADMIN), isAdmin = false)).isNull()
        assertThat(NotificationsLogic.navFor(null, isAdmin = true)).isNull()
    }

    @Test
    fun `rows group by local day with today and yesterday labels`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 10, 21)
        val items = listOf(
            notification("a", "2026-10-21T02:00:00Z"), // 10:00 本地,今天
            notification("b", "2026-10-20T17:30:00Z"), // 01:30 本地,今天
            notification("c", "2026-10-20T15:00:00Z"), // 23:00 本地,昨天
            notification("d", "2026-10-01T04:00:00Z"),
            notification("e", "2025-12-31T04:00:00Z"),
        )
        val rows = NotificationsLogic.rows(items, zone, today)
        val labels = rows.map {
            when (it) {
                is NotificationRow.DayHeader -> "#" + it.label
                is NotificationRow.Item -> it.notification.id
            }
        }
        assertThat(labels).containsExactly("#今天", "a", "b", "#昨天", "c", "#10月1日", "d", "#2025-12-31", "e").inOrder()
        assertThat(rows.map { it.key }.toSet()).hasSize(rows.size)
    }

    @Test
    fun `unparseable times stay under the current day`() {
        val rows = NotificationsLogic.rows(listOf(notification("x", "garbage")), ZoneId.of("UTC"), LocalDate.of(2026, 10, 21))
        assertThat((rows.first() as NotificationRow.DayHeader).label).isEqualTo("今天")
    }

    @Test
    fun `badge text caps at 99`() {
        assertThat(NotificationsLogic.badgeText(7)).isEqualTo("7")
        assertThat(NotificationsLogic.badgeText(99)).isEqualTo("99")
        assertThat(NotificationsLogic.badgeText(100)).isEqualTo("99+")
    }
}
