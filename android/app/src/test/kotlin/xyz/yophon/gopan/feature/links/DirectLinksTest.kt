package xyz.yophon.gopan.feature.links

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.links.DirectLink
import xyz.yophon.gopan.core.data.links.FakeDirectLinksRepository
import xyz.yophon.gopan.core.data.links.FakeDirectLinksRepository.Companion.link
import xyz.yophon.gopan.core.data.links.LinkStatus
import xyz.yophon.gopan.core.data.links.LinkUsage
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.shares.SharesTab
import xyz.yophon.gopan.feature.shares.SharesTabRequest
import java.time.Instant

/** 「复制直链」与「直链」列表 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectLinksTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeDirectLinksRepository()
    private val tabRequest = SharesTabRequest()

    private fun UiText.resId(): Int? = (this as? UiText.Res)?.id

    // ---- 复制直链 ----

    @Test
    fun `copy creates links for files only, in the selected order`() = runTest {
        val vm = CopyLinkViewModel(repo)
        val messages = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        advanceUntilIdle()
        assertThat(vm.state.value.enabled).isTrue()

        vm.copy(listOf(FakeNodesRepository.file("a"), FakeNodesRepository.folder("dir"), FakeNodesRepository.file("b")))
        advanceUntilIdle()
        assertThat(repo.calls).contains("create(a,b)")
        val pending = vm.state.value.pending!!
        assertThat(pending.links.map { it.name }).containsExactly("a.jpg", "b.jpg").inOrder()
        assertThat(pending.links.all { it.isImage }).isTrue()
        assertThat(messages.map { it.resId() }).containsExactly(R.string.links_msg_skipped_folders)

        // Android 12 及以下:系统不提示,补一句「已复制」
        vm.onCopied(systemToast = true)
        assertThat(vm.state.value.pending).isNull()
        assertThat(messages.last().resId()).isEqualTo(R.string.links_msg_copied_many)
    }

    @Test
    fun `folders alone cannot get a link`() = runTest {
        val vm = CopyLinkViewModel(repo)
        val messages = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        vm.copy(listOf(FakeNodesRepository.folder("dir")))
        advanceUntilIdle()
        assertThat(repo.calls.none { it.startsWith("create") }).isTrue()
        assertThat(messages.map { it.resId() }).containsExactly(R.string.links_msg_folder)
        assertThat(vm.state.value.pending).isNull()
    }

    @Test
    fun `photos with gps get a warning after copying`() = runTest {
        repo.gpsNodes += "p1"
        val vm = CopyLinkViewModel(repo)
        val messages = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        vm.copy(listOf(FakeNodesRepository.file("p1")))
        advanceUntilIdle()
        assertThat(vm.state.value.pending!!.gpsCount).isEqualTo(1)
        // Android 13+ 系统自己提示「已复制」,这里仍要提醒位置
        vm.onCopied(systemToast = false)
        assertThat(messages.map { it.resId() }).containsExactly(R.string.links_gps_warning)

        repo.gpsNodes += "p2"
        vm.copy(listOf(FakeNodesRepository.file("p1"), FakeNodesRepository.file("p2"), FakeNodesRepository.file("x")))
        advanceUntilIdle()
        vm.onCopied(systemToast = true)
        assertThat(messages.last().resId()).isEqualTo(R.string.links_gps_warning_many)
    }

    @Test
    fun `nothing is shown when links are off or the call fails`() = runTest {
        repo.enabled = false
        val vm = CopyLinkViewModel(repo)
        val messages = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        advanceUntilIdle()
        assertThat(vm.state.value.enabled).isFalse()

        repo.failNext = true
        vm.copy(listOf(FakeNodesRepository.file("a")))
        advanceUntilIdle()
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.pending).isNull()
        assertThat((messages.single() as UiText.Plain).text).isEqualTo("网络连接失败,请检查网络后重试")
    }

    // ---- 直链列表 ----

    @Test
    fun `list loads when the tab is first shown and pages on`() = runTest {
        repo.pageSize = 2
        repo.links = (1..3).map { link("l$it") }
        val vm = DirectLinksViewModel(repo, tabRequest)
        advanceUntilIdle()
        assertThat(vm.state.value.enabled).isTrue()
        assertThat(repo.calls.none { it.startsWith("myLinks") }).isTrue()

        vm.onShown()
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.total).isEqualTo(3)
        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("l1", "l2", "l3").inOrder()
        assertThat(vm.state.value.nextCursor).isNull()
    }

    @Test
    fun `pause, resume and revoke update the list`() = runTest {
        repo.links = listOf(link("l1"), link("l2"))
        val vm = DirectLinksViewModel(repo, tabRequest)
        val effects = mutableListOf<DirectLinksEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.onShown()
        advanceUntilIdle()

        vm.setPaused(vm.state.value.items[0], paused = true)
        advanceUntilIdle()
        assertThat(vm.state.value.items[0].status).isEqualTo(LinkStatus.PAUSED)
        vm.setPaused(vm.state.value.items[0], paused = false)
        advanceUntilIdle()
        assertThat(vm.state.value.items[0].status).isEqualTo(LinkStatus.ACTIVE)

        vm.revoke(vm.state.value.items[1])
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("l1")
        assertThat(vm.state.value.total).isEqualTo(1)

        // 已经不在了也当成功
        repo.missing += "l1"
        vm.revoke(vm.state.value.items[0])
        advanceUntilIdle()
        assertThat(vm.state.value.items).isEmpty()
        val ids = effects.filterIsInstance<DirectLinksEffect.Message>().map { it.text.resId() }
        assertThat(ids).containsExactly(R.string.links_msg_paused, R.string.links_msg_resumed, R.string.links_msg_revoked, R.string.links_msg_revoked).inOrder()
    }

    @Test
    fun `focus opens a link from the list, or fetches one that is not on the first page`() = runTest {
        repo.pageSize = 1
        repo.links = listOf(link("l1"), link("l2"))
        val vm = DirectLinksViewModel(repo, tabRequest)
        val effects = mutableListOf<DirectLinksEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }

        // 还没显示过列表:focus 自己先拉第一页
        vm.focus("l1")
        advanceUntilIdle()
        assertThat(effects.single()).isEqualTo(DirectLinksEffect.Focus("l1"))
        assertThat(repo.calls.none { it.startsWith("link(") }).isTrue()

        vm.focus("l2")
        advanceUntilIdle()
        assertThat(repo.calls).contains("link(l2)")
        assertThat(vm.state.value.items.first().id).isEqualTo("l2")
        assertThat(effects.last()).isEqualTo(DirectLinksEffect.Focus("l2"))

        // 刷新后仍在最上面(操作表开着)
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("l2", "l1").inOrder()

        repo.missing += "gone"
        vm.focus("gone")
        advanceUntilIdle()
        assertThat((effects.last() as DirectLinksEffect.Message).text.resId()).isEqualTo(R.string.links_msg_gone)
    }

    @Test
    fun `tab requests are exposed and consumed`() = runTest {
        val vm = DirectLinksViewModel(repo, tabRequest)
        tabRequest.request(SharesTab.LINKS, "l1")
        val target = vm.pendingTab.value!!
        assertThat(target.linkId).isEqualTo("l1")
        vm.consumeTab(target)
        assertThat(vm.pendingTab.value).isNull()
    }

    @Test
    fun `list load failure without data shows the error page`() = runTest {
        repo.links = listOf(link("l1"))
        val vm = DirectLinksViewModel(repo, tabRequest)
        advanceUntilIdle()
        repo.failNext = true
        vm.onShown()
        advanceUntilIdle()
        assertThat(vm.state.value.status).isInstanceOf(ListStatus.Error::class.java)
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.items).hasSize(1)
    }

    // ---- 文案 ----

    @Test
    fun `status texts and notes`() {
        val active = link("a")
        assertThat(DirectLinkTexts.status(active).resId()).isEqualTo(R.string.links_status_active)
        assertThat(DirectLinkTexts.note(active)).isNull()
        assertThat(DirectLinkTexts.canPause(active)).isTrue()
        assertThat(DirectLinkTexts.canResume(active)).isFalse()

        val cap = active.copy(status = LinkStatus.PAUSED, pauseReason = "daily_cap")
        assertThat(DirectLinkTexts.status(cap).resId()).isEqualTo(R.string.links_status_paused_cap)
        assertThat(DirectLinkTexts.note(cap)?.resId()).isEqualTo(R.string.links_note_cap)
        assertThat(DirectLinkTexts.canResume(cap)).isTrue()
        assertThat(DirectLinkTexts.status(active.copy(status = LinkStatus.PAUSED, pauseReason = "anomaly")).resId())
            .isEqualTo(R.string.links_status_paused_anomaly)
        val manual = active.copy(status = LinkStatus.PAUSED, pauseReason = "manual")
        assertThat(DirectLinkTexts.status(manual).resId()).isEqualTo(R.string.links_status_paused)
        assertThat(DirectLinkTexts.note(manual)).isNull()

        val expired = active.copy(status = LinkStatus.EXPIRED, expiresAt = "2026-10-01T00:00:00Z")
        assertThat(DirectLinkTexts.status(expired).resId()).isEqualTo(R.string.links_status_expired)
        assertThat(DirectLinkTexts.note(expired)?.resId()).isEqualTo(R.string.links_note_expired)
        assertThat(DirectLinkTexts.canPause(expired)).isFalse()
        assertThat(DirectLinkTexts.canResume(expired)).isFalse()

        val gone = active.copy(status = LinkStatus.UNAVAILABLE)
        assertThat(DirectLinkTexts.status(gone).resId()).isEqualTo(R.string.links_status_unavailable)
        assertThat(DirectLinkTexts.note(gone)?.resId()).isEqualTo(R.string.links_note_unavailable)
    }

    @Test
    fun `usage and last hit texts`() {
        val l: DirectLink = link("a").copy(
            today = LinkUsage(hits = 3, bytes = 2048),
            last30Days = LinkUsage(hits = 120, bytes = 5 * 1024 * 1024),
            lastHitAt = "2026-10-21T09:55:00Z",
        )
        val usage = DirectLinkTexts.usage(l) as UiText.Res
        assertThat(usage.id).isEqualTo(R.string.links_usage)
        assertThat(usage.formatArgs.toList()).containsExactly(3L, "2.0 KB", 120L, "5.0 MB").inOrder()

        val last = DirectLinkTexts.lastHit(l, now = Instant.parse("2026-10-21T10:00:00Z")) as UiText.Res
        assertThat(last.id).isEqualTo(R.string.links_last_hit)
        assertThat(last.formatArgs.toList()).containsExactly("5 分钟前")
        assertThat(DirectLinkTexts.lastHit(link("b")).resId()).isEqualTo(R.string.links_no_hits)
    }
}
