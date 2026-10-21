package xyz.yophon.gopan.feature.media

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.media.FakeMediaRepository
import xyz.yophon.gopan.core.data.media.PlayProgress
import xyz.yophon.gopan.core.data.media.RecentPlay
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.preview.PreviewSessionStore

@OptIn(ExperimentalCoroutinesApi::class)
class RecentPlaysViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeMediaRepository()
    private val sessions = PreviewSessionStore()

    private fun play(id: String, position: Double = 100.0) =
        RecentPlay(Node(id = id, name = "$id.mp4", kind = NodeKind.FILE), PlayProgress(position, 6000.0, false, "2026-10-20T08:00:00Z"))

    @Test
    fun `loads the unfinished plays`() = runTest {
        repo.recent = listOf(play("a"), play("b"))
        val vm = RecentPlaysViewModel(repo, sessions)
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Loading)
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items.map { it.node.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a failed first load shows an error that can be retried`() = runTest {
        repo.recentError = GopanException(GopanErrors.NETWORK, "网络连接失败")
        val vm = RecentPlaysViewModel(repo, sessions)
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Error("网络连接失败"))
        repo.recentError = null
        repo.recent = listOf(play("a"))
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.node.id }).containsExactly("a")
    }

    @Test
    fun `coming back from the preview refreshes quietly`() = runTest {
        repo.recent = listOf(play("a"), play("b"))
        val vm = RecentPlaysViewModel(repo, sessions)
        advanceUntilIdle()
        vm.onResumed() // 刚打开
        // 看完了 a:回来时它不在了
        repo.recent = listOf(play("b", position = 200.0))
        vm.onResumed()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.node.id }).containsExactly("b")
        assertThat(vm.state.value.items.single().progress.position).isEqualTo(200.0)
    }

    @Test
    fun `opening a play previews just that file`() = runTest {
        repo.recent = listOf(play("a"), play("b"))
        val vm = RecentPlaysViewModel(repo, sessions)
        advanceUntilIdle()
        vm.effects.test {
            vm.open(vm.state.value.items[1])
            assertThat(awaitItem()).isEqualTo(RecentPlaysEffect.OpenPreview("b"))
        }
        assertThat(sessions.itemsFor("b").map { it.nodeId }).containsExactly("b")
    }

    @Test
    fun `removing clears the progress and drops the row`() = runTest {
        repo.recent = listOf(play("a"), play("b"))
        val vm = RecentPlaysViewModel(repo, sessions)
        advanceUntilIdle()
        vm.effects.test {
            vm.remove(vm.state.value.items[0])
            assertThat(vm.state.value.items.map { it.node.id }).containsExactly("b")
            val effect = awaitItem() as RecentPlaysEffect.Message
            assertThat((effect.text as UiText.Res).id).isEqualTo(R.string.media_recent_removed)
        }
        assertThat(repo.cleared).containsExactly("a")
    }

    @Test
    fun `a failed removal puts the row back where it was`() = runTest {
        repo.recent = listOf(play("a"), play("b"), play("c"))
        repo.clearError = GopanException(GopanErrors.NETWORK, "网络连接失败")
        val vm = RecentPlaysViewModel(repo, sessions)
        advanceUntilIdle()
        vm.effects.test {
            vm.remove(vm.state.value.items[1])
            assertThat(vm.state.value.items.map { it.node.id }).containsExactly("a", "c").inOrder()
            assertThat(awaitItem()).isEqualTo(RecentPlaysEffect.Message(UiText.Plain("网络连接失败")))
        }
        assertThat(vm.state.value.items.map { it.node.id }).containsExactly("a", "b", "c").inOrder()
    }
}
