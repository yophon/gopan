package xyz.yophon.gopan.feature.archive

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.archive.ArchiveFlags
import xyz.yophon.gopan.core.data.archive.ArchiveStatus
import xyz.yophon.gopan.core.data.archive.ExtractRequest
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.share.AppNavRequests
import xyz.yophon.gopan.core.share.NavRequest
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository.Companion.archiveInfo
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository.Companion.dir
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository.Companion.file
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val repo = FakeArchiveRepository()
    private val navRequests = AppNavRequests()

    private fun viewModel(parentId: String? = "parent-1"): ArchiveViewModel {
        val args = buildMap {
            put(ArchiveViewModel.NODE_ID_ARG, "arc-1")
            put(ArchiveViewModel.NAME_ARG, "资料.zip")
            if (parentId != null) put(ArchiveViewModel.PARENT_ID_ARG, parentId)
        }
        return ArchiveViewModel(SavedStateHandle(args), repo, navRequests)
    }

    private fun TestScope.effectsOf(vm: ArchiveViewModel): List<ArchiveEffect> {
        val list = mutableListOf<ArchiveEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { list += it } }
        return list
    }

    private fun List<ArchiveEffect>.messageIds(): List<Int> = filterIsInstance<ArchiveEffect.Message>().mapNotNull { (it.text as? UiText.Res)?.id }

    @Test
    fun `while the index is being built it polls every two seconds and stops once done`() = runTest {
        repo.respondInfo(archiveInfo(ArchiveStatus.PENDING), archiveInfo(ArchiveStatus.PENDING), archiveInfo(ArchiveStatus.DONE))
        repo.page("", dir(0, "docs"), file(1, "readme.txt"))
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.info).isEqualTo(ArchiveInfoState.Pending)
        assertThat(repo.infoCalls).isEqualTo(1)
        assertThat(repo.entryCalls).isEmpty()

        advanceTimeBy(ArchiveViewModel.POLL_MS)
        runCurrent()
        assertThat(repo.infoCalls).isEqualTo(2)

        advanceUntilIdle()
        assertThat(repo.infoCalls).isEqualTo(3)
        assertThat(vm.state.value.info).isInstanceOf(ArchiveInfoState.Ready::class.java)
        assertThat(vm.state.value.entries.map { it.path }).containsExactly("docs", "readme.txt").inOrder()

        // 读完了就不再问
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(repo.infoCalls).isEqualTo(3)
    }

    @Test
    fun `an archive the server cannot read shows its reason and lists nothing`() = runTest {
        repo.respondInfo(archiveInfo(ArchiveStatus.UNSUPPORTED, error = "压缩的 tar 超过 4 GB,请下载后解压"))
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.info).isEqualTo(ArchiveInfoState.Unavailable("压缩的 tar 超过 4 GB,请下载后解压"))
        assertThat(repo.entryCalls).isEmpty()
    }

    @Test
    fun `a failed first request can be retried`() = runTest {
        repo.infoError = GopanException(GopanErrors.NETWORK, "网络连接失败")
        repo.respondInfo(archiveInfo())
        repo.page("", file(1, "a.txt"))
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.info).isEqualTo(ArchiveInfoState.Error("网络连接失败"))

        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.info).isInstanceOf(ArchiveInfoState.Ready::class.java)
        assertThat(vm.state.value.entries).hasSize(1)
    }

    @Test
    fun `directories are entered one at a time and back goes up one level before leaving`() = runTest {
        repo.respondInfo(archiveInfo())
        repo.page("", dir(0, "docs"), file(1, "a.txt"))
        repo.page("docs", dir(2, "docs/2024"), file(3, "docs/b.txt"))
        repo.page("docs/2024", file(4, "docs/2024/c.png"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEntryClick(dir(0, "docs"))
        advanceUntilIdle()
        assertThat(vm.state.value.dir).isEqualTo("docs")
        assertThat(vm.state.value.crumbs).containsExactly(ArchiveCrumb("docs", "docs"))

        vm.onEntryClick(dir(2, "docs/2024"))
        advanceUntilIdle()
        assertThat(vm.state.value.crumbs).containsExactly(ArchiveCrumb("docs", "docs"), ArchiveCrumb("2024", "docs/2024")).inOrder()
        assertThat(vm.state.value.entries.map { it.name }).containsExactly("c.png")

        // 返回:一级一级往上,看过的目录直接显示,不再请求
        assertThat(vm.onBack()).isTrue()
        assertThat(vm.state.value.dir).isEqualTo("docs")
        assertThat(vm.state.value.listStatus).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.entries.map { it.path }).containsExactly("docs/2024", "docs/b.txt").inOrder()
        assertThat(vm.onBack()).isTrue()
        assertThat(vm.state.value.dir).isEmpty()
        assertThat(vm.onBack()).isFalse()
        advanceUntilIdle()
        assertThat(repo.entryCalls).containsExactly("" to null, "docs" to null, "docs/2024" to null).inOrder()

        // 面包屑直接跳
        vm.openDir("docs/2024")
        assertThat(vm.state.value.dir).isEqualTo("docs/2024")
        assertThat(vm.state.value.canGoUp).isTrue()
    }

    @Test
    fun `pages are fetched with the cursor until there is none`() = runTest {
        repo.respondInfo(archiveInfo())
        repo.page("", file(1, "a.txt"), file(2, "b.txt"), next = "c1", total = 3)
        repo.page("", file(3, "c.txt"), cursor = "c1", total = 3)
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.nextCursor).isEqualTo("c1")
        assertThat(vm.state.value.total).isEqualTo(3)

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.entries.map { it.name }).containsExactly("a.txt", "b.txt", "c.txt").inOrder()
        assertThat(vm.state.value.nextCursor).isNull()

        vm.loadMore()
        advanceUntilIdle()
        assertThat(repo.entryCalls).containsExactly("" to null, "" to "c1").inOrder()
    }

    @Test
    fun `long press selects, flagged entries cannot be selected, and changing directory clears it`() = runTest {
        repo.respondInfo(archiveInfo())
        val docs = dir(0, "docs")
        val a = file(1, "a.txt")
        val secret = file(2, "secret.txt", flag = ArchiveFlags.ENCRYPTED)
        repo.page("", docs, a, secret)
        repo.page("docs", file(3, "docs/b.txt"))
        val vm = viewModel()
        val effects = effectsOf(vm)
        advanceUntilIdle()

        vm.onEntryLongClick(secret)
        assertThat(vm.state.value.selectionMode).isFalse()
        assertThat(effects.messageIds()).containsExactly(R.string.archive_flag_msg_encrypted)

        vm.onEntryLongClick(a)
        assertThat(vm.state.value.selection).containsExactly("a.txt")
        // 多选时点目录是勾选,不是进入
        vm.onEntryClick(docs)
        assertThat(vm.state.value.selection).containsExactly("a.txt", "docs").inOrder()
        assertThat(vm.state.value.dir).isEmpty()
        vm.onEntryClick(secret)
        assertThat(vm.state.value.selection).doesNotContain("secret.txt")
        vm.toggle(secret)
        assertThat(vm.state.value.selection).doesNotContain("secret.txt")

        vm.onEntryClick(a)
        assertThat(vm.state.value.selection).containsExactly("docs")
        vm.selectAll()
        assertThat(vm.state.value.selection).containsExactly("docs", "a.txt")
        assertThat(vm.state.value.allSelected).isTrue()

        // 返回先退出多选
        assertThat(vm.onBack()).isTrue()
        assertThat(vm.state.value.selectionMode).isFalse()

        vm.onEntryLongClick(a)
        vm.openDir("docs")
        assertThat(vm.state.value.selection).isEmpty()
    }

    @Test
    fun `extracting takes the current directory as base and the selected full paths`() = runTest {
        repo.respondInfo(archiveInfo())
        repo.page("", dir(0, "docs"))
        repo.page("docs", dir(2, "docs/2024"), file(3, "docs/b.txt"), file(4, "docs/c.txt"))
        val vm = viewModel()
        val effects = effectsOf(vm)
        advanceUntilIdle()

        // 根目录、没勾:整包解到压缩包旁边
        assertThat(vm.state.value.extractLabel).isEqualTo(ExtractLabel.All)
        assertThat(vm.extractRequest()).isEqualTo(ExtractRequest(nodeId = "arc-1", base = null, paths = emptyList(), parentId = "parent-1"))

        vm.openDir("docs")
        advanceUntilIdle()
        assertThat(vm.state.value.extractLabel).isEqualTo(ExtractLabel.CurrentDir)
        assertThat(vm.extractRequest()).isEqualTo(ExtractRequest("arc-1", base = "docs", paths = emptyList(), parentId = "parent-1"))

        vm.onEntryLongClick(dir(2, "docs/2024"))
        vm.onEntryClick(file(3, "docs/b.txt"))
        assertThat(vm.state.value.extractLabel).isEqualTo(ExtractLabel.Selected(2))
        val expected = ExtractRequest("arc-1", base = "docs", paths = listOf("docs/2024", "docs/b.txt"), parentId = "parent-1")
        assertThat(vm.extractRequest()).isEqualTo(expected)

        vm.extractHere()
        assertThat(vm.extract.state.value.busy).isTrue()
        advanceUntilIdle()
        assertThat(repo.extracts).containsExactly(expected)
        assertThat(effects).contains(ArchiveEffect.ExtractStarted)
        assertThat(vm.state.value.selection).isEmpty()
        assertThat(vm.extract.state.value.busy).isFalse()

        // 「查看」:请主界面打开传输列表
        vm.showTransfers()
        assertThat(navRequests.request.value).isEqualTo(NavRequest.OPEN_TRANSFERS)
    }

    @Test
    fun `an archive in the root extracts into the root by default`() = runTest {
        repo.respondInfo(archiveInfo())
        repo.page("", file(1, "a.txt"))
        val vm = viewModel(parentId = null)
        advanceUntilIdle()
        vm.extractHere()
        advanceUntilIdle()
        assertThat(repo.extracts.single().parentId).isNull()
    }

    @Test
    fun `extract to a chosen folder keeps failures inside the picker`() = runTest {
        repo.respondInfo(archiveInfo())
        repo.page("", file(1, "a.txt"))
        val vm = viewModel()
        val effects = effectsOf(vm)
        advanceUntilIdle()

        vm.extractTo()
        val picker = vm.extract.state.value.picker
        assertThat(picker).isNotNull()

        repo.extractError = GopanException(GopanErrors.QUOTA_EXCEEDED, "存储空间不足")
        vm.extract.onPicked("folder-9")
        advanceUntilIdle()
        assertThat(repo.extracts.last().parentId).isEqualTo("folder-9")
        assertThat(vm.extract.state.value.picker?.error).isEqualTo("存储空间不足")
        assertThat(effects).isEmpty()

        // 选根目录再提交:成功后选择器关掉
        vm.extract.onPicked(null)
        advanceUntilIdle()
        assertThat(repo.extracts.last()).isEqualTo(ExtractRequest("arc-1", base = null, paths = emptyList(), parentId = null))
        assertThat(vm.extract.state.value.picker).isNull()
        assertThat(effects).containsExactly(ArchiveEffect.ExtractStarted)
    }

    @Test
    fun `a failed extraction without the picker becomes a message`() = runTest {
        repo.respondInfo(archiveInfo())
        repo.page("", file(1, "a.txt"))
        val vm = viewModel()
        val effects = effectsOf(vm)
        advanceUntilIdle()
        repo.extractError = GopanException("ARCHIVE_NOTHING", "选中的条目里没有能解压的文件")
        vm.extractHere()
        advanceUntilIdle()
        assertThat((effects.single() as ArchiveEffect.Message).text).isEqualTo(UiText.Plain("选中的条目里没有能解压的文件"))
    }

    @Test
    fun `previewable files open in the viewer by kind and text is read in segments`() = runTest {
        val png = tmp.newFile("p.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val txt = tmp.newFile("t.txt").apply { writeText("0123456789") }
        repo.respondInfo(archiveInfo())
        val image = file(5, "p.png", size = 3)
        val text = file(6, "t.txt", size = 10)
        repo.page("", image, text)
        repo.files[5] = png
        repo.files[6] = txt
        repo.textMaxBytes = 4
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEntryClick(image)
        assertThat(vm.state.value.viewer).isInstanceOf(EntryViewer.Loading::class.java)
        advanceUntilIdle()
        assertThat(vm.state.value.viewer).isEqualTo(EntryViewer.Image(image, png))
        assertThat(vm.onBack()).isTrue()
        assertThat(vm.state.value.viewer).isNull()

        vm.onEntryClick(text)
        advanceUntilIdle()
        val first = vm.state.value.viewer as EntryViewer.Text
        assertThat(first.text.content.text).isEqualTo("0123")
        assertThat(first.text.content.truncated).isTrue()
        vm.loadMoreText()
        advanceUntilIdle()
        vm.loadMoreText()
        advanceUntilIdle()
        val all = vm.state.value.viewer as EntryViewer.Text
        assertThat(all.text.content.text).isEqualTo("0123456789")
        assertThat(all.text.content.truncated).isFalse()
        assertThat(all.file).isEqualTo(txt)
    }

    @Test
    fun `a failed fetch shows the server reason and can be retried`() = runTest {
        val pdf = tmp.newFile("doc.pdf")
        repo.respondInfo(archiveInfo())
        val entry = file(7, "doc.pdf")
        repo.page("", entry)
        repo.files[7] = pdf
        repo.fetchError = GopanException("ARCHIVE_ENTRY_TOO_DEEP", "要先解开前面的 300 MB 才能读到它,请解压后再看")
        val vm = viewModel()
        advanceUntilIdle()

        vm.onEntryClick(entry)
        advanceUntilIdle()
        assertThat(vm.state.value.viewer).isEqualTo(EntryViewer.Failed(entry, "要先解开前面的 300 MB 才能读到它,请解压后再看"))

        vm.retryViewer()
        advanceUntilIdle()
        assertThat(vm.state.value.viewer).isEqualTo(EntryViewer.Pdf(entry, pdf))
    }

    @Test
    fun `other downloadable files are fetched and handed to other apps`() = runTest {
        val exe = tmp.newFile("tool.exe")
        repo.respondInfo(archiveInfo())
        val entry = file(8, "tool.exe", previewable = false, downloadable = true)
        repo.page("", entry)
        repo.files[8] = exe
        val vm = viewModel()
        val effects = effectsOf(vm)
        advanceUntilIdle()

        vm.onEntryClick(entry)
        assertThat(vm.state.value.fileMenu).isEqualTo(entry)
        vm.fetchForApp(entry, share = true)
        assertThat(vm.state.value.fileMenu).isNull()
        assertThat(vm.state.value.fetching?.entry).isEqualTo(entry)
        advanceUntilIdle()
        assertThat(vm.state.value.fetching).isNull()
        assertThat(effects).containsExactly(ArchiveEffect.LaunchFile(exe, share = true))
    }

    @Test
    fun `files that cannot be opened say why`() = runTest {
        repo.respondInfo(archiveInfo())
        val big = file(9, "movie.mkv", size = 300L * 1024 * 1024, previewable = false, downloadable = false)
        val deep = file(10, "deep.txt", size = 10, previewable = false, downloadable = false)
        val link = file(11, "link", flag = ArchiveFlags.LINK)
        repo.page("", big, deep, link)
        val vm = viewModel()
        val effects = effectsOf(vm)
        advanceUntilIdle()

        vm.onEntryClick(big)
        vm.onEntryClick(deep)
        vm.onEntryClick(link)
        assertThat(effects.messageIds())
            .containsExactly(R.string.archive_entry_too_large, R.string.archive_entry_too_deep, R.string.archive_flag_msg_link)
            .inOrder()
        assertThat(vm.state.value.viewer).isNull()
        assertThat(repo.fetched).isEmpty()
    }
}
