package xyz.yophon.gopan.feature.notifications

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.notifications.FakeNotificationsRepository
import xyz.yophon.gopan.core.data.notifications.FakeNotificationsRepository.Companion.notification
import xyz.yophon.gopan.core.data.notifications.NotificationTarget
import xyz.yophon.gopan.core.data.notifications.NotificationTargetKind
import xyz.yophon.gopan.core.data.notifications.UnreadNotifications
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.shares.SharesTab
import xyz.yophon.gopan.feature.shares.SharesTabRequest
import xyz.yophon.gopan.feature.transfers.TransfersTabRequest

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeNotificationsRepository()
    private val unread = UnreadNotifications(repo)
    private val nodes = FakeNodesRepository()
    private val session = SessionStore().apply { setSignedIn("t", UserSummary("u1", "alice", 0, 0, isAdmin = false)) }
    private val sharesTab = SharesTabRequest()
    private val transfersTab = TransfersTabRequest()

    private fun vmTest(block: suspend TestScope.(NotificationsViewModel, List<NotificationsEffect>) -> Unit) = runTest {
        val vm = NotificationsViewModel(repo, unread, nodes, session, sharesTab, transfersTab)
        val effects = mutableListOf<NotificationsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        block(vm, effects)
    }

    private fun List<NotificationsEffect>.navs() = filterIsInstance<NotificationsEffect.Navigate>().map { it.nav }

    private fun List<NotificationsEffect>.messageIds() =
        filterIsInstance<NotificationsEffect.Message>().mapNotNull { (it.text as? UiText.Res)?.id }

    @Test
    fun `first page loads and the badge is synced`() {
        repo.items = listOf(notification("n1"), notification("n2", read = true), notification("n3"))
        vmTest { vm, _ ->
            assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
            assertThat(vm.state.value.items.map { it.id }).containsExactly("n1", "n2", "n3").inOrder()
            assertThat(vm.state.value.hasUnread).isTrue()
            assertThat(unread.count.value).isEqualTo(2)
        }
    }

    @Test
    fun `pages are appended until the cursor runs out`() {
        repo.pageSize = 2
        repo.items = (1..5).map { notification("n$it") }
        vmTest { vm, _ ->
            assertThat(vm.state.value.nextCursor).isEqualTo("2")
            vm.loadMore()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            assertThat(vm.state.value.items.map { it.id }).containsExactly("n1", "n2", "n3", "n4", "n5").inOrder()
            assertThat(vm.state.value.nextCursor).isNull()
            vm.loadMore()
            advanceUntilIdle()
            assertThat(repo.calls.count { it.startsWith("list") }).isEqualTo(3)
        }
    }

    @Test
    fun `opening a file notification marks it read and previews it in its folder`() {
        repo.items = listOf(notification("n1", target = NotificationTarget(NotificationTargetKind.NODE, "f1", "dir")))
        nodes.nodes["f1"] = FakeNodesRepository.file("f1", parentId = "dir")
        vmTest { vm, effects ->
            assertThat(unread.count.value).isEqualTo(1)
            vm.open(vm.state.value.items.single())
            advanceUntilIdle()
            assertThat(vm.state.value.items.single().read).isTrue()
            assertThat(unread.count.value).isEqualTo(0)
            assertThat(repo.calls).contains("markRead(n1)")
            assertThat(effects.navs()).containsExactly(NotificationNav.Drive(folderId = "dir", previewId = "f1"))
            assertThat(vm.state.value.opening).isNull()
        }
    }

    @Test
    fun `opening a folder notification goes into the folder`() {
        repo.items = listOf(notification("n1", read = true, target = NotificationTarget(NotificationTargetKind.NODE, "x1", null)))
        nodes.nodes["x1"] = FakeNodesRepository.folder("x1")
        vmTest { vm, effects ->
            vm.open(vm.state.value.items.single())
            advanceUntilIdle()
            assertThat(effects.navs()).containsExactly(NotificationNav.Drive(folderId = "x1"))
            // 已读的不再标
            assertThat(repo.calls.none { it.startsWith("markRead") }).isTrue()
        }
    }

    @Test
    fun `deleted or trashed nodes only show a message`() {
        repo.items = listOf(
            notification("gone", target = NotificationTarget(NotificationTargetKind.NODE, "missing", "dir")),
            notification("trash", target = NotificationTarget(NotificationTargetKind.NODE, "t1", "dir")),
        )
        nodes.nodes["t1"] = FakeNodesRepository.file("t1").copy(deletedAt = "2026-10-21T00:00:00Z")
        vmTest { vm, effects ->
            vm.open(vm.state.value.items[0])
            advanceUntilIdle()
            vm.open(vm.state.value.items[1])
            advanceUntilIdle()
            assertThat(effects.navs()).isEmpty()
            assertThat(effects.messageIds()).containsExactly(R.string.notifications_msg_gone, R.string.notifications_msg_in_trash).inOrder()
        }
    }

    @Test
    fun `links, file requests and jobs ask their screens to switch tabs`() {
        repo.items = listOf(
            notification("a", target = NotificationTarget(NotificationTargetKind.LINK, "l1")),
            notification("b", target = NotificationTarget(NotificationTargetKind.FILE_REQUEST, "r1")),
            notification("c", target = NotificationTarget(NotificationTargetKind.JOBS)),
            notification("d", target = NotificationTarget(NotificationTargetKind.CREDENTIALS, "mcp")),
        )
        vmTest { vm, effects ->
            val (a, b, c, d) = vm.state.value.items
            vm.open(a)
            assertThat(sharesTab.pending.value?.tab).isEqualTo(SharesTab.LINKS)
            assertThat(sharesTab.pending.value?.linkId).isEqualTo("l1")
            vm.open(b)
            assertThat(sharesTab.pending.value?.tab).isEqualTo(SharesTab.COLLECT)
            vm.open(c)
            assertThat(transfersTab.consumeJobs()).isTrue()
            vm.open(d)
            advanceUntilIdle()
            assertThat(effects.navs()).containsExactly(
                NotificationNav.Links("l1"),
                NotificationNav.FileRequests("r1"),
                NotificationNav.Jobs,
                NotificationNav.Agent,
            ).inOrder()
            assertThat(unread.count.value).isEqualTo(0)
        }
    }

    @Test
    fun `admin targets are ignored for non-admins and notifications without targets only get read`() {
        repo.items = listOf(
            notification("a", target = NotificationTarget(NotificationTargetKind.ADMIN)),
            notification("b"),
        )
        vmTest { vm, effects ->
            vm.state.value.items.forEach(vm::open)
            advanceUntilIdle()
            assertThat(effects.navs()).isEmpty()
            assertThat(vm.state.value.items.all { it.read }).isTrue()
        }
    }

    @Test
    fun `mark all read clears the badge, and empties the unread-only view`() {
        repo.items = listOf(notification("n1"), notification("n2"))
        vmTest { vm, effects ->
            vm.markAllRead()
            advanceUntilIdle()
            assertThat(repo.calls).contains("markRead(null)")
            assertThat(vm.state.value.items.all { it.read }).isTrue()
            assertThat(unread.count.value).isEqualTo(0)
            assertThat(effects.messageIds()).contains(R.string.notifications_msg_all_read)

            repo.items = listOf(notification("n3"))
            vm.setUnreadOnly(true)
            advanceUntilIdle()
            assertThat(repo.calls.last { it.startsWith("list") }).isEqualTo("list(null,true)")
            assertThat(vm.state.value.items.map { it.id }).containsExactly("n3")
            vm.markAllRead()
            advanceUntilIdle()
            assertThat(vm.state.value.items).isEmpty()
        }
    }

    @Test
    fun `clear removes everything`() {
        repo.pageSize = 1
        repo.items = listOf(notification("n1"), notification("n2"))
        vmTest { vm, effects ->
            vm.clearAll()
            advanceUntilIdle()
            assertThat(vm.state.value.items).isEmpty()
            assertThat(vm.state.value.nextCursor).isNull()
            assertThat(unread.count.value).isEqualTo(0)
            assertThat(effects.messageIds()).contains(R.string.notifications_msg_cleared)
        }
    }

    @Test
    fun `load failure shows the error page and retry recovers`() {
        repo.items = listOf(notification("n1"))
        repo.failNext = true
        vmTest { vm, _ ->
            assertThat(vm.state.value.status).isInstanceOf(ListStatus.Error::class.java)
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
            assertThat(vm.state.value.items).hasSize(1)
        }
    }

    @Test
    fun `failed mark-all keeps the list and says why`() {
        repo.items = listOf(notification("n1"))
        vmTest { vm, effects ->
            repo.failNext = true
            vm.markAllRead()
            advanceUntilIdle()
            assertThat(vm.state.value.items.single().read).isFalse()
            assertThat(vm.state.value.busy).isFalse()
            assertThat(effects.filterIsInstance<NotificationsEffect.Message>().map { (it.text as UiText.Plain).text })
                .containsExactly("网络连接失败,请检查网络后重试")
        }
    }
}
