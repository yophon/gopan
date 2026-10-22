package xyz.yophon.gopan.feature.feeds

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.feeds.FakeFeedsRepository
import xyz.yophon.gopan.core.data.feeds.Feed
import xyz.yophon.gopan.core.data.feeds.FeedItem
import xyz.yophon.gopan.core.data.feeds.FeedItemNode
import xyz.yophon.gopan.core.data.feeds.FeedItemState
import xyz.yophon.gopan.core.data.feeds.FeedKind
import xyz.yophon.gopan.core.data.feeds.FeedMode
import xyz.yophon.gopan.core.data.feeds.FeedPreview
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText

/** 添加订阅与订阅详情 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeFeedsRepository()
    private val nodes = FakeNodesRepository()

    private val podcast = Feed(
        id = "f1",
        kind = FeedKind.RSS,
        url = "https://example.com/podcast.xml",
        title = "播客",
        folderId = "dir",
        folderName = "播客",
        mode = FeedMode.DOWNLOAD,
        keepLast = 5,
        intervalMinutes = 720,
    )

    // ---- 列表 ----

    @Test
    fun `list - a second visit shows the last list at once, then the fresh one`() = runTest {
        val cache = ScreenCache(SessionStore().apply { setSignedIn("t", UserSummary("u1", "alice", 0, 0, false)) })
        repo.feeds = listOf(podcast)
        val first = FeedsViewModel(repo, cache)
        advanceUntilIdle()
        assertThat(first.state.value.feeds).containsExactly(podcast)

        val newer = podcast.copy(id = "f2", title = "新订阅")
        repo.feeds = listOf(podcast, newer)
        val gate = CompletableDeferred<Unit>()
        repo.feedsGate = gate
        val second = FeedsViewModel(repo, cache)
        advanceUntilIdle()
        // 联网还没回来:上次的列表已经画出来了
        assertThat(second.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(second.state.value.feeds).containsExactly(podcast)

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(second.state.value.feeds).containsExactly(podcast, newer).inOrder()
    }

    // ---- 添加 ----

    @Test
    fun `add - preview preselects the suggested mode and creates with it`() = runTest {
        repo.preview = FeedPreview(kind = FeedKind.RSS, url = "https://example.com/podcast.xml", title = "播客", podcast = true, suggestedMode = FeedMode.DOWNLOAD)
        val vm = FeedAddViewModel(repo)
        val created = mutableListOf<Feed>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.created.collect { created += it } }

        assertThat(vm.state.value.canPreview).isFalse()
        vm.setUrl("  https://example.com/podcast.xml ")
        vm.preview()
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("preview(https://example.com/podcast.xml,)")
        assertThat(vm.state.value.mode).isEqualTo(FeedMode.DOWNLOAD)
        assertThat(vm.state.value.canCreate).isTrue()

        // RSS 能选剪藏;GitHub 不支持的不让选
        vm.setMode(FeedMode.CLIP)
        assertThat(vm.state.value.mode).isEqualTo(FeedMode.CLIP)
        vm.create()
        advanceUntilIdle()
        assertThat(repo.calls.last()).isEqualTo("create(https://example.com/podcast.xml,CLIP,null)")
        assertThat(created.single().id).isEqualTo("new")
    }

    @Test
    fun `add - changing the url needs a new preview, errors are shown`() = runTest {
        repo.preview = FeedPreview(kind = FeedKind.GITHUB, url = "owner/repo", title = "repo", suggestedMode = FeedMode.DOWNLOAD)
        val vm = FeedAddViewModel(repo)
        vm.setUrl("owner/repo")
        vm.setAssetPattern("*.apk")
        vm.preview()
        advanceUntilIdle()
        assertThat(repo.calls.single()).isEqualTo("preview(owner/repo,*.apk)")
        vm.setMode(FeedMode.CLIP)
        assertThat(vm.state.value.mode).isEqualTo(FeedMode.DOWNLOAD)

        vm.setUrl("owner/other")
        assertThat(vm.state.value.previewStale).isTrue()
        assertThat(vm.state.value.canCreate).isFalse()
        vm.create()
        advanceUntilIdle()
        assertThat(repo.calls.none { it.startsWith("create") }).isTrue()

        repo.preview = null
        vm.preview()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo("不是 RSS / Atom")
    }

    // ---- 详情 ----

    private fun detail(): FeedDetailViewModel = FeedDetailViewModel(SavedStateHandle(mapOf("feedId" to "f1")), repo, nodes, ScreenCache(SessionStore()))

    @Test
    fun `detail loads the feed and its items`() = runTest {
        repo.feeds = listOf(podcast)
        repo.itemPages += listOf(FeedItem("i1", "第 2 集"), FeedItem("i2", "第 1 集", state = FeedItemState.SKIPPED))
        val vm = detail()
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.feed).isEqualTo(podcast)
        assertThat(vm.state.value.items.map { it.id }).containsExactly("i1", "i2").inOrder()
    }

    @Test
    fun `pause sends the whole feed, resume flips it back`() = runTest {
        repo.feeds = listOf(podcast)
        val vm = detail()
        val effects = mutableListOf<FeedDetailEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()

        vm.toggleEnabled()
        advanceUntilIdle()
        assertThat(repo.lastUpdated).isEqualTo(podcast to false)
        assertThat(vm.state.value.feed?.enabled).isFalse()
        assertThat(((effects.last() as FeedDetailEffect.Message).text as UiText.Res).id).isEqualTo(R.string.feeds_msg_paused)

        vm.toggleEnabled()
        advanceUntilIdle()
        assertThat(repo.lastUpdated?.second).isTrue()
        assertThat(vm.state.value.feed?.enabled).isTrue()
    }

    @Test
    fun `fetching an item marks it queued and polls while visible until it is done`() = runTest {
        repo.feeds = listOf(podcast)
        repo.itemPages += listOf(FeedItem("i1", "第 1 集", enclosureUrl = "https://cdn.example.com/1.mp3"))
        val vm = detail()
        advanceUntilIdle()
        vm.setVisible(true)
        advanceUntilIdle()

        // 下一轮还在处理,再下一轮下好了(轮询在处理中时一直转,这里不能 advanceUntilIdle)
        repo.itemPages += listOf(FeedItem("i1", "第 1 集", state = FeedItemState.QUEUED))
        repo.itemPages += listOf(FeedItem("i1", "第 1 集", state = FeedItemState.DONE, node = FeedItemNode("n1", "第 1 集.mp3", false, "dir")))
        vm.fetch(vm.state.value.items.single(), FeedMode.DOWNLOAD)
        runCurrent()
        assertThat(repo.calls).contains("fetchItem(i1,DOWNLOAD)")
        assertThat(vm.state.value.items.single().state).isEqualTo(FeedItemState.QUEUED)

        advanceTimeBy(FeedDetailViewModel.POLL_MS + 1)
        assertThat(vm.state.value.items.single().state).isEqualTo(FeedItemState.QUEUED)
        advanceTimeBy(FeedDetailViewModel.POLL_MS)
        runCurrent()
        assertThat(vm.state.value.items.single().state).isEqualTo(FeedItemState.DONE)
        val polls = repo.calls.count { it == "items(f1,null)" }

        // 都结束了:不再轮询
        advanceTimeBy(FeedDetailViewModel.POLL_MS * 5)
        assertThat(repo.calls.count { it == "items(f1,null)" }).isEqualTo(polls)
        vm.setVisible(false)
    }

    @Test
    fun `unsubscribe closes the page`() = runTest {
        repo.feeds = listOf(podcast)
        val vm = detail()
        val effects = mutableListOf<FeedDetailEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.unsubscribe()
        advanceUntilIdle()
        assertThat(repo.calls).contains("delete(f1)")
        assertThat(effects).contains(FeedDetailEffect.Closed)
        assertThat(repo.feeds).isEmpty()
    }
}
