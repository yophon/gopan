package xyz.yophon.gopan.feature.preview

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import xyz.yophon.gopan.core.data.quick.FakeQuickRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.NodePreview
import xyz.yophon.gopan.core.data.archive.ExtractRequest
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.data.media.FakeMediaRepository
import xyz.yophon.gopan.core.data.media.LyricsRepository
import xyz.yophon.gopan.core.data.media.MediaUrls
import xyz.yophon.gopan.core.data.media.NowPlaying
import xyz.yophon.gopan.core.data.media.NowPlayingStore
import xyz.yophon.gopan.core.data.media.PlayProgressReporter
import xyz.yophon.gopan.core.data.media.QueueEntry
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.share.AppNavRequests
import xyz.yophon.gopan.core.share.NavRequest
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository
import xyz.yophon.gopan.feature.drive.FakeTransferActions
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakePreviewRepository()
    private val archives = FakeArchiveRepository()
    private val transfers = FakeTransferActions()
    private val sessions = PreviewSessionStore()
    private val navRequests = AppNavRequests()
    private val media = FakeMediaRepository()
    private val reporter = PlayProgressReporter(media, CoroutineScope(UnconfinedTestDispatcher()))
    private val urlFetches = mutableListOf<String>()
    private val mediaUrls = MediaUrls(fetch = { urlFetches += it; "https://s3/$it?fresh" }, now = { 0L })
    private val nowPlaying = NowPlayingStore()
    private val offline = FakeOfflineLookup()

    private fun file(id: String) = Node(id = id, name = "$id.jpg", kind = NodeKind.FILE, size = 10)

    private val lyrics = LyricsRepository(OkHttpClient(), AppConfig("https://drive.example.com", "https://id.example.com", "c", "r"))

    private fun viewModel(start: String, current: String? = null): PreviewViewModel {
        val args = buildMap {
            put(PreviewViewModel.NODE_ID_ARG, start)
            if (current != null) put("preview.current", current)
        }
        return PreviewViewModel(SavedStateHandle(args), sessions, repo, transfers, OkHttpClient(), archives, navRequests, media, reporter, mediaUrls, lyrics, nowPlaying, FakeQuickRepository(), offline)
    }

    @Test
    fun `pages through the files of the list it was opened from, folders left out`() {
        sessions.open(listOf(file("a"), Node(id = "f", name = "dir", kind = NodeKind.FOLDER), file("b"), file("c")))
        val vm = viewModel("b")
        assertThat(vm.items.map { it.nodeId }).containsExactly("a", "b", "c").inOrder()
        assertThat(vm.initialPage).isEqualTo(1)
    }

    @Test
    fun `a session from content search carries each file's hit page, others open at the first page`() {
        // 内容搜索结果:PDF 带命中页;0 / 负数不是合法页码,当作没有
        sessions.open(listOf(file("a"), file("b"), file("c"), file("d")), pdfPages = mapOf("a" to 3, "c" to 0, "d" to -1))
        val vm = viewModel("b")
        assertThat(vm.items.map { it.nodeId }).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(vm.items.map { it.pdfPage }).containsExactly(3, null, null, null).inOrder()
        assertThat(vm.initialPage).isEqualTo(1)

        // 其它入口(文件页、文件名搜索、传输助手)照旧:没有起始页
        sessions.open(listOf(file("a"), file("b")))
        assertThat(viewModel("a").items.map { it.pdfPage }).containsExactly(null, null)
    }

    @Test
    fun `the side pane restart picks up the hit pages of the new session`() {
        sessions.open(listOf(file("a")))
        val vm = viewModel("a")
        sessions.open(listOf(file("x"), file("y")), pdfPages = mapOf("y" to 7))
        vm.restart("y")
        assertThat(vm.session.value.items.map { it.pdfPage }).containsExactly(null, 7).inOrder()
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
        val vm = PreviewViewModel(handle, sessions, repo, transfers, OkHttpClient(), archives, navRequests, media, reporter, mediaUrls, lyrics, nowPlaying, FakeQuickRepository(), offline)
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

    @Test
    fun `the archive card extracts next to the archive or into a picked folder`() = runTest {
        val zip = info("z", PreviewKind.ARCHIVE, name = "资料.zip")
        val vm = viewModel("z")
        vm.effects.test {
            vm.extractHere(zip)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(PreviewEffect.ExtractStarted)
            assertThat(archives.extracts.last()).isEqualTo(ExtractRequest(nodeId = "z", base = null, paths = emptyList(), parentId = "p"))

            vm.extractTo(zip)
            assertThat(vm.extract.state.value.picker?.request?.nodeId).isEqualTo("z")
            vm.extract.onPicked("folder-2")
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(PreviewEffect.ExtractStarted)
            assertThat(archives.extracts.last().parentId).isEqualTo("folder-2")
            assertThat(vm.extract.state.value.picker).isNull()

            archives.extractError = GopanException("TOO_MANY_JOBS", "进行中的任务太多,稍后再试")
            vm.extractHere(zip)
            advanceUntilIdle()
            assertThat((awaitItem() as PreviewEffect.Message).text).isEqualTo(UiText.Plain("进行中的任务太多,稍后再试"))
        }
        vm.showTransfers()
        assertThat(navRequests.request.value).isEqualTo(NavRequest.OPEN_JOBS)
    }

    // ---- 影音(doc/22 二) ----

    private fun media(id: String, kind: PreviewKind) =
        Node(id = id, name = "$id.bin", kind = NodeKind.FILE, size = 10, preview = NodePreview(kind = kind))

    @Test
    fun `an audio file queues the whole folder and hands over the url it already has`() = runTest {
        sessions.open(listOf(file("b")))
        repo.respond("b", info("b", PreviewKind.AUDIO, contentUrl = "https://s3/b?sig=1"))
        media.playlists["b"] = listOf(media("a", PreviewKind.AUDIO), media("b", PreviewKind.AUDIO), media("c", PreviewKind.AUDIO))
        val vm = viewModel("b")
        vm.page("b")
        advanceUntilIdle()

        val plan = vm.loadAudioQueue("b", "b.mp3")
        assertThat(plan.entries.map { it.nodeId }).containsExactly("a", "b", "c").inOrder()
        assertThat(plan.startIndex).isEqualTo(1)
        // 第一首不用再查地址;其它的开播时才查
        assertThat(mediaUrls.resolve("b")).isEqualTo("https://s3/b?sig=1")
        assertThat(mediaUrls.resolve("c")).isEqualTo("https://s3/c?fresh")
        assertThat(urlFetches).containsExactly("c")
    }

    @Test
    fun `without a playlist the audio file plays on its own`() = runTest {
        sessions.open(listOf(file("b")))
        media.playlistError = GopanException(GopanErrors.NETWORK, "网络连接失败")
        val vm = viewModel("b")
        assertThat(vm.loadAudioQueue("b", "b.mp3")).isEqualTo(xyz.yophon.gopan.feature.media.QueuePlan(listOf(QueueEntry("b", "b.mp3")), 0))
    }

    @Test
    fun `the next episode comes from the folder playlist`() = runTest {
        sessions.open(listOf(file("e1")))
        media.playlists["e1"] = listOf(media("e1", PreviewKind.VIDEO), media("e2", PreviewKind.VIDEO))
        media.playlists["e2"] = media.playlists.getValue("e1")
        val vm = viewModel("e1")
        val next = vm.nextVideo("e1")
        assertThat(next?.next?.id).isEqualTo("e2")
        assertThat(vm.nextVideo("e2")).isNull()
        media.playlistError = GopanException(GopanErrors.NETWORK, "网络连接失败")
        assertThat(vm.nextVideo("e1")).isNull()
    }

    @Test
    fun `an episode outside the session opens the folder playlist from there`() = runTest {
        sessions.open(listOf(file("e1")))
        val playlist = listOf(media("e1", PreviewKind.VIDEO), media("e2", PreviewKind.VIDEO), media("e3", PreviewKind.VIDEO))
        val vm = viewModel("e1")
        vm.openPlaylist(UpNext(playlist[1], playlist))
        val session = vm.session.value
        assertThat(session.items.map { it.nodeId }).containsExactly("e1", "e2", "e3").inOrder()
        assertThat(session.initialPage).isEqualTo(1)
        assertThat(session.generation).isEqualTo(1)
    }

    @Test
    fun `progress reports go through the reporter`() {
        sessions.open(listOf(file("v")))
        val vm = viewModel("v")
        vm.reportMedia("v", 61_000, 6_000_000)
        vm.reportPdf("p", 12, 300)
        vm.reportMedia("song", 61_000, 180_000)
        assertThat(media.saved).containsExactly(Triple("v", 61.0, 6000.0), Triple("p", 12.0, 300.0)).inOrder()
    }

    @Test
    fun `now playing comes from the playback service`() {
        sessions.open(listOf(file("a")))
        val vm = viewModel("a")
        assertThat(vm.nowPlaying.value).isNull()
        val now = NowPlaying("a", "a.mp3", true, true, 0, 1000, listOf(QueueEntry("a", "a.mp3")))
        nowPlaying.update(now)
        assertThat(vm.nowPlaying.value).isEqualTo(now)
        nowPlaying.requestStop()
        assertThat(vm.nowPlaying.value).isNull()
    }
}
