package xyz.yophon.gopan.feature.preview

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.feature.drive.FakeTransferActions
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakePreviewRepository()
    private val transfers = FakeTransferActions()
    private val sessions = PreviewSessionStore()

    private fun file(id: String) = Node(id = id, name = "$id.jpg", kind = NodeKind.FILE, size = 10)

    private fun viewModel(start: String, current: String? = null): PreviewViewModel {
        val args = buildMap {
            put(PreviewViewModel.NODE_ID_ARG, start)
            if (current != null) put("preview.current", current)
        }
        return PreviewViewModel(SavedStateHandle(args), sessions, repo, transfers, OkHttpClient())
    }

    @Test
    fun `pages through the files of the list it was opened from, folders left out`() {
        sessions.open(listOf(file("a"), Node(id = "f", name = "dir", kind = NodeKind.FOLDER), file("b"), file("c")))
        val vm = viewModel("b")
        assertThat(vm.items.map { it.nodeId }).containsExactly("a", "b", "c").inOrder()
        assertThat(vm.initialPage).isEqualTo(1)
    }

    @Test
    fun `after the process is recreated only the file being viewed is left`() {
        // 会话列表在内存里,进程重建后没了;SavedStateHandle 里记着翻到的那一项
        val vm = viewModel("a", current = "c")
        assertThat(vm.items.map { it.nodeId }).containsExactly("c")
        assertThat(vm.initialPage).isEqualTo(0)
    }

    @Test
    fun `the settled page is remembered for recreation`() {
        sessions.open(listOf(file("a"), file("b")))
        val handle = SavedStateHandle(mapOf(PreviewViewModel.NODE_ID_ARG to "a"))
        val vm = PreviewViewModel(handle, sessions, repo, transfers, OkHttpClient())
        vm.onPageSettled(1)
        assertThat(vm.current.value).isEqualTo(1)
        assertThat(handle.get<String>("preview.current")).isEqualTo("b")
    }

    @Test
    fun `pages far away are released but pages on screen are kept`() = runTest {
        val ids = ('a'..'j').map { it.toString() }
        sessions.open(ids.map { file(it) })
        ids.forEach { repo.respond(it, info(it, PreviewKind.IMAGE, largeUrl = "https://s3/$it-large")) }
        val vm = viewModel("a")

        val a = vm.page("a")
        vm.attach("a")
        val b = vm.page("b")
        ids.drop(2).forEach { vm.page(it) }
        advanceUntilIdle()

        // a 在屏幕上:还是同一个;b 最久没用又不在屏幕上:被释放,再取是新的
        assertThat(vm.page("a")).isSameInstanceAs(a)
        assertThat(vm.page("b")).isNotSameInstanceAs(b)
    }

    @Test
    fun `download uses the loaded info of the current file`() = runTest {
        sessions.open(listOf(file("a")))
        repo.respond("a", info("a", PreviewKind.IMAGE, name = "服务端名字.jpg", largeUrl = "https://s3/a-large"))
        val vm = viewModel("a")
        vm.page("a")
        advanceUntilIdle()

        vm.effects.test {
            vm.download()
            assertThat(awaitItem()).isInstanceOf(PreviewEffect.Message::class.java)
            assertThat(awaitItem()).isEqualTo(PreviewEffect.TransferQueued)
        }
        assertThat(transfers.downloaded.single().single().name).isEqualTo("服务端名字.jpg")
    }

    @Test
    fun `download on android 9 waits for the storage permission`() = runTest {
        sessions.open(listOf(file("a")))
        transfers.needsPermission = true
        val vm = viewModel("a")

        vm.effects.test {
            vm.download()
            assertThat(awaitItem()).isEqualTo(PreviewEffect.RequestStoragePermission)
            vm.onStoragePermission(false)
            val denied = awaitItem() as PreviewEffect.Message
            assertThat((denied.text as UiText.Res).id).isEqualTo(R.string.transfer_msg_need_storage)
            assertThat(transfers.downloaded).isEmpty()

            vm.download()
            assertThat(awaitItem()).isEqualTo(PreviewEffect.RequestStoragePermission)
            vm.onStoragePermission(true)
            assertThat(awaitItem()).isInstanceOf(PreviewEffect.Message::class.java)
            assertThat(awaitItem()).isEqualTo(PreviewEffect.TransferQueued)
        }
        // 列表带来的信息也能下载(页面还没加载完时)
        assertThat(transfers.downloaded.single().single().name).isEqualTo("a.jpg")
    }

    @Test
    fun `open with and send to chat act on the current file`() = runTest {
        sessions.open(listOf(file("a"), file("b")))
        val vm = viewModel("a")
        vm.onPageSettled(1)
        vm.openWith()
        vm.sendToChat()
        advanceUntilIdle()
        assertThat(transfers.opened.single().id).isEqualTo("b")
        assertThat(transfers.sent.single().id).isEqualTo("b")
    }

    @Test
    fun `the side pane restarts on another file with a fresh session and releases old pages`() = runTest {
        sessions.open(listOf(file("a"), file("b")))
        repo.respond("a", info("a", PreviewKind.IMAGE, largeUrl = "https://s3/a-large"))
        val vm = viewModel("a")
        val oldPage = vm.page("a")
        advanceUntilIdle()
        assertThat(vm.session.value.generation).isEqualTo(0)

        // 停在当前文件上:不动
        vm.restart("a")
        assertThat(vm.session.value.generation).isEqualTo(0)

        // 侧栏点了另一个列表里的文件
        sessions.open(listOf(file("x"), file("y"), file("z")))
        vm.restart("y")
        val s = vm.session.value
        assertThat(s.generation).isEqualTo(1)
        assertThat(s.items.map { it.nodeId }).containsExactly("x", "y", "z").inOrder()
        assertThat(s.initialPage).isEqualTo(1)
        assertThat(vm.current.value).isEqualTo(1)
        assertThat(vm.page("a")).isNotSameInstanceAs(oldPage)
    }

    @Test
    fun `actions before anything is known about the file just say so`() = runTest {
        val vm = viewModel("a", current = "z")
        vm.effects.test {
            vm.download()
            val msg = awaitItem() as PreviewEffect.Message
            assertThat((msg.text as UiText.Res).id).isEqualTo(R.string.preview_not_loaded)
        }
        assertThat(transfers.downloaded).isEmpty()
    }
}
