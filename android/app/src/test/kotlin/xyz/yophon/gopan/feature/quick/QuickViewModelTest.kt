package xyz.yophon.gopan.feature.quick

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.InMemorySnapshotDisk
import xyz.yophon.gopan.core.data.NodePreview
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.media.FakeMediaRepository
import xyz.yophon.gopan.core.data.media.PlayProgress
import xyz.yophon.gopan.core.data.media.RecentPlay
import xyz.yophon.gopan.core.data.quick.FakeQuickRepository
import xyz.yophon.gopan.core.data.quick.QuickRepository
import xyz.yophon.gopan.core.data.quick.FileVersion
import xyz.yophon.gopan.core.data.quick.FileVersionList
import xyz.yophon.gopan.core.data.quick.QuickItem
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.file
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.folder
import xyz.yophon.gopan.feature.drive.FakeTransferActions
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.NodeEffect
import xyz.yophon.gopan.feature.preview.PreviewSessionStore

@OptIn(ExperimentalCoroutinesApi::class)
class QuickViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeNodesRepository()
    private val quick = FakeQuickRepository()
    private val media = FakeMediaRepository()
    private val sessions = PreviewSessionStore()
    private val cache = ScreenCache(SessionStore().apply { setSignedIn("t", UserSummary("u1", "u1", 0, 0, false)) }, InMemorySnapshotDisk())

    private fun viewModel(recent: Boolean) =
        QuickViewModel(SavedStateHandle(mapOf(QuickViewModel.RECENT_ARG to recent)), repo, FakeTransferActions(), sessions, quick, cache, media)

    @Test
    fun `prefetched snapshot shows at once, then refreshes silently and writes back`() = runTest {
        cache.put(QuickRepository.STARRED_KEY, listOf(QuickItem(folder("old", "旧的"), "/旧的")))
        quick.starredItems = listOf(QuickItem(folder("d1", "工作"), "/工作"))
        val vm = viewModel(recent = false)
        // 构造完立刻就是上次的快照,不经过加载中
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items.map { it.node.id }).containsExactly("old")
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.node.id }).containsExactly("d1")
        assertThat(cache[QuickRepository.STARRED_KEY]?.map { it.node.id }).containsExactly("d1")
    }

    @Test
    fun `starred loads folders and files, folder opens and file previews with file siblings`() = runTest {
        quick.starredItems = listOf(
            QuickItem(folder("d1", "工作"), "/工作"),
            QuickItem(file("f1", "合同.pdf", parentId = "d1"), "/工作/合同.pdf"),
            QuickItem(file("f2", "报价.xlsx"), "/报价.xlsx"),
        )
        val vm = viewModel(recent = false)
        advanceUntilIdle()
        assertThat(quick.calls).containsExactly("starred")
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)

        vm.effects.test {
            vm.onItemClick(quick.starredItems[0].node)
            assertThat(awaitItem()).isEqualTo(NodeEffect.OpenFolder("d1"))
            vm.onItemClick(quick.starredItems[2].node)
            assertThat(awaitItem()).isEqualTo(NodeEffect.OpenPreview("f2"))
        }
        // 左右翻的只有列表里的文件
        assertThat(sessions.itemsFor("f2").map { it.nodeId }).containsExactly("f1", "f2").inOrder()
    }

    @Test
    fun `forget drops one item, clear all empties the recent list`() = runTest {
        quick.recentItems = listOf(
            QuickItem(file("f1", "a.txt"), "/a.txt", openedAt = "2026-10-24T01:00:00Z"),
            QuickItem(file("f2", "b.txt"), "/b.txt", openedAt = "2026-10-24T00:00:00Z"),
        )
        val vm = viewModel(recent = true)
        advanceUntilIdle()
        assertThat(quick.calls).containsExactly("recent")

        vm.forget(quick.recentItems[0].node)
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.node.id }).containsExactly("f2")
        assertThat(quick.calls).contains("clearRecent:f1")

        vm.askClear()
        assertThat(vm.state.value.confirmClear).isTrue()
        vm.clearAll()
        advanceUntilIdle()
        assertThat(vm.state.value.items).isEmpty()
        assertThat(quick.calls).contains("clearRecent:all")
    }

    @Test
    fun `recent shows resume strip and filters by kind, forgetting progress keeps the row`() = runTest {
        val video = file("v1", "电影.mkv").copy(preview = NodePreview(kind = PreviewKind.VIDEO))
        val pdf = file("p1", "手册.pdf").copy(preview = NodePreview(kind = PreviewKind.PDF))
        val pic = file("i1", "照片.jpg").copy(preview = NodePreview(kind = PreviewKind.IMAGE))
        val progress = PlayProgress(position = 600.0, duration = 6000.0, finished = false)
        quick.recentItems = listOf(
            QuickItem(video, "/电影.mkv", openedAt = "2026-10-28T01:00:00Z", progress = progress),
            QuickItem(pdf, "/手册.pdf", openedAt = "2026-10-28T00:30:00Z"),
            QuickItem(pic, "/照片.jpg", openedAt = "2026-10-28T00:00:00Z"),
        )
        media.recent = listOf(RecentPlay(video, progress))
        val vm = viewModel(recent = true)
        advanceUntilIdle()
        assertThat(vm.state.value.plays.map { it.node.id }).containsExactly("v1")
        assertThat(vm.state.value.items[0].progress).isEqualTo(progress)

        vm.setFilter(RecentFilter.MEDIA)
        assertThat(vm.state.value.shown.map { it.node.id }).containsExactly("v1")
        vm.setFilter(RecentFilter.DOC)
        assertThat(vm.state.value.shown.map { it.node.id }).containsExactly("p1")
        vm.setFilter(RecentFilter.ALL)
        assertThat(vm.state.value.shown).hasSize(3)

        // 不再接着看:那一排里没了,列表那一行还在、只是不写看到哪
        vm.forgetProgress(vm.state.value.plays[0])
        advanceUntilIdle()
        assertThat(media.cleared).containsExactly("v1")
        assertThat(vm.state.value.plays).isEmpty()
        assertThat(vm.state.value.items.first { it.node.id == "v1" }.progress).isNull()

        // 清空:接着看也一起空
        media.recent = listOf(RecentPlay(video, progress))
        vm.refresh()
        advanceUntilIdle()
        vm.clearAll()
        advanceUntilIdle()
        assertThat(vm.state.value.items).isEmpty()
        assertThat(vm.state.value.plays).isEmpty()
    }

    @Test
    fun `versions restore asks first, then restores and reports a change`() = runTest {
        quick.versionList = FileVersionList(
            listOf(FileVersion("v1", 10, "2026-10-23T00:00:00Z", "2026-10-24T00:00:00Z", "https://s3/v1")),
            keep = 10,
            keepDays = 30,
        )
        val vm = VersionsViewModel(quick)
        vm.load("f1")
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("v1")
        assertThat(vm.state.value.policy).isEqualTo(10 to 30)

        vm.events.test {
            vm.askRestore(vm.state.value.items[0])
            assertThat(quick.calls).doesNotContain("restore:v1")
            vm.confirm()
            assertThat(awaitItem()).isInstanceOf(VersionsEvent.Changed::class.java)
        }
        assertThat(quick.calls).contains("restore:v1")
        assertThat(vm.state.value.confirm).isNull()
    }
}
