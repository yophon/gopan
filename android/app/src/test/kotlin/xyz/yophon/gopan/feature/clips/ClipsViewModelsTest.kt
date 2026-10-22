package xyz.yophon.gopan.feature.clips

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.clips.FakeClipsRepository
import xyz.yophon.gopan.core.data.markdown.MarkdownUrls
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule

/** 剪藏列表、阅读页、预览里的 Markdown 页 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeClipsRepository()
    private val nodes = FakeNodesRepository()
    private val urls = MarkdownUrls(
        AppConfig("https://pan.example.com", "https://id.example.com", "c", "r"),
        SessionStore().apply { setSignedIn("tok", UserSummary("u1", "me", 0, 0, false)) },
    ) {
        object : TokenRefresher {
            override suspend fun refreshAfterUnauthenticated(staleToken: String?): RefreshOutcome = RefreshOutcome.Refreshed("fresh")
        }
    }

    @Test
    fun `list filters unread`() = runTest {
        repo.clips = listOf(FakeClipsRepository.clip("a"), FakeClipsRepository.clip("b", read = true))
        val vm = ClipsViewModel(repo)
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a", "b").inOrder()

        vm.setUnreadOnly(true)
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a")
        assertThat(repo.calls.last()).isEqualTo("clips(unread=true,null)")
    }

    private fun reader(clipId: String? = "a") = ClipReaderViewModel(
        SavedStateHandle(listOfNotNull("nodeId" to "n-a", clipId?.let { "clipId" to it }).toMap()),
        repo,
        nodes,
        urls,
    )

    @Test
    fun `reader builds the md url for the theme and marks read after a few seconds on screen`() = runTest {
        repo.clips = listOf(FakeClipsRepository.clip("a"))
        val vm = reader()
        vm.load(dark = true)
        advanceUntilIdle()
        assertThat(vm.state.value.url).isEqualTo("https://pan.example.com/md/n-a?token=tok&theme=dark")
        assertThat(vm.state.value.clip?.unread).isTrue()

        vm.setVisible(true)
        advanceTimeBy(ClipLogic.AUTO_READ_MS - 1)
        assertThat(repo.calls.none { it.startsWith("markRead") }).isTrue()
        advanceTimeBy(2)
        runCurrent()
        assertThat(repo.calls).contains("markRead(a,true)")
        assertThat(vm.state.value.clip?.unread).isFalse()

        // 深浅变了重拼地址
        vm.load(dark = false)
        advanceUntilIdle()
        assertThat(vm.state.value.url).endsWith("theme=light")
    }

    @Test
    fun `leaving before the delay does not mark read, manual toggle does`() = runTest {
        repo.clips = listOf(FakeClipsRepository.clip("a"))
        val vm = reader(clipId = null)
        advanceUntilIdle()
        assertThat(repo.calls).contains("nodeClip(n-a)")
        vm.setVisible(true)
        advanceTimeBy(1_000)
        vm.setVisible(false)
        advanceUntilIdle()
        assertThat(repo.calls.none { it.startsWith("markRead") }).isTrue()

        vm.setRead(true)
        advanceUntilIdle()
        assertThat(vm.state.value.clip?.unread).isFalse()
        vm.setRead(false)
        advanceUntilIdle()
        assertThat(vm.state.value.clip?.unread).isTrue()
        // 手动标回未读之后不再自动标
        vm.setVisible(true)
        advanceUntilIdle()
        assertThat(vm.state.value.clip?.unread).isTrue()
    }

    @Test
    fun `delete moves the md to the trash and closes`() = runTest {
        repo.clips = listOf(FakeClipsRepository.clip("a"))
        val vm = reader()
        val effects = mutableListOf<ClipReaderEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.delete()
        advanceUntilIdle()
        assertThat(nodes.calls).contains("delete([n-a])")
        assertThat(effects).contains(ClipReaderEffect.Closed)
    }

    @Test
    fun `markdown page in the preview finds the clip and marks it read`() = runTest {
        repo.clips = listOf(FakeClipsRepository.clip("a"))
        val vm = MarkdownPageViewModel(urls, repo)
        vm.bind("n-a", dark = true)
        vm.bind("n-a", dark = true)
        advanceUntilIdle()
        assertThat(vm.state.value.url).isEqualTo("https://pan.example.com/md/n-a?token=tok&theme=dark")
        assertThat(vm.state.value.clip?.id).isEqualTo("a")
        assertThat(repo.calls.count { it == "nodeClip(n-a)" }).isEqualTo(1)
        vm.markRead()
        advanceUntilIdle()
        assertThat(vm.state.value.clip?.unread).isFalse()

        // 不是剪藏的 .md:没有来源条
        val plain = MarkdownPageViewModel(urls, repo)
        plain.bind("other", dark = true)
        advanceUntilIdle()
        assertThat(plain.state.value.clip).isNull()
        assertThat(plain.state.value.url).contains("/md/other?")
    }
}
