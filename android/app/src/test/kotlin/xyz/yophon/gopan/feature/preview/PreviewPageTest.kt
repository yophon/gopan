package xyz.yophon.gopan.feature.preview

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.archive.ArchiveStatus
import xyz.yophon.gopan.core.data.preview.DerivativeStatus
import xyz.yophon.gopan.core.data.preview.TextContent
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.archive.ArchiveSummary
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository.Companion.archiveInfo

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewPageTest {

    private val repo = FakePreviewRepository()
    private val archives = FakeArchiveRepository()

    /** 页面的协程跑在前台测试调度器上(advanceUntilIdle 会推进它),测试结束取消(轮询不会挂住 runTest) */
    private var pageScope: CoroutineScope? = null

    private fun TestScope.page(id: String, attached: Boolean = true): PreviewPage {
        val scope = pageScope ?: CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { pageScope = it }
        return PreviewPage(id, repo, scope, archives).also {
            it.setAttached(attached)
            it.start()
        }
    }

    @After
    fun tearDown() {
        pageScope?.cancel()
    }

    @Test
    fun `an image shows the 2048 derivative first and can switch to the original`() = runTest {
        repo.respond("a", info("a", PreviewKind.IMAGE, largeUrl = "https://s3/a-large", contentUrl = "https://s3/a"))
        val page = page("a")
        advanceUntilIdle()

        val first = page.state.value
        assertThat(first.content).isEqualTo(PreviewContent.Image("https://s3/a-large", "sha-a#large", original = false))
        assertThat(first.canShowOriginal).isTrue()

        page.showOriginal()
        val second = page.state.value
        assertThat(second.content).isEqualTo(PreviewContent.Image("https://s3/a", "sha-a#original", original = true))
        assertThat(second.canShowOriginal).isFalse()
    }

    @Test
    fun `an image without a derivative shows the original directly`() = runTest {
        repo.respond("a", info("a", PreviewKind.IMAGE, largeUrl = null, contentUrl = "https://s3/a"))
        val page = page("a")
        advanceUntilIdle()

        assertThat(page.state.value.content).isEqualTo(PreviewContent.Image("https://s3/a", "sha-a#original", original = true))
        assertThat(page.state.value.canShowOriginal).isFalse()
    }

    @Test
    fun `content without a sha falls back to id and update time for the cache key`() = runTest {
        repo.respond("a", info("a", PreviewKind.PDF, sha256 = null))
        val page = page("a")
        advanceUntilIdle()

        assertThat(repo.pdfs.single().second).isEqualTo("a@2026-10-14T00:00:00Z")
        assertThat(page.state.value.content).isInstanceOf(PreviewContent.Pdf::class.java)
    }

    @Test
    fun `office never converted is requested once and polled until the pdf is ready`() = runTest {
        repo.respond(
            "d",
            info("d", PreviewKind.OFFICE, contentUrl = null, status = null),
            info("d", PreviewKind.OFFICE, contentUrl = null, status = DerivativeStatus.PENDING),
            info("d", PreviewKind.OFFICE, contentUrl = null, status = DerivativeStatus.RUNNING),
            info("d", PreviewKind.OFFICE, contentUrl = "https://s3/d.pdf", status = DerivativeStatus.DONE),
        )
        val page = page("d")
        runCurrent()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.OfficeConverting)
        assertThat(repo.requested).containsExactly("d")

        advanceUntilIdle()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.Pdf(java.io.File("preview-pdf/sha-d.office.pdf")))
        assertThat(repo.pdfs.single()).isEqualTo("https://s3/d.pdf" to "sha-d.office")
        assertThat(repo.requested).containsExactly("d")
        assertThat(repo.previewCalls["d"]).isEqualTo(4)
    }

    @Test
    fun `office polling pauses while the page is off screen`() = runTest {
        repo.respond(
            "d",
            info("d", PreviewKind.OFFICE, contentUrl = null, status = DerivativeStatus.PENDING),
            info("d", PreviewKind.OFFICE, contentUrl = "https://s3/d.pdf", status = DerivativeStatus.DONE),
        )
        val page = page("d", attached = false)
        advanceTimeBy(60_000)
        assertThat(repo.previewCalls["d"]).isEqualTo(1)
        assertThat(page.state.value.content).isEqualTo(PreviewContent.OfficeConverting)

        page.setAttached(true)
        advanceUntilIdle()
        assertThat(repo.previewCalls["d"]).isEqualTo(2)
        assertThat(page.state.value.content).isInstanceOf(PreviewContent.Pdf::class.java)
    }

    @Test
    fun `office on a site without conversion asks to open with another app`() = runTest {
        repo.respond("d", info("d", PreviewKind.OFFICE, contentUrl = null, status = DerivativeStatus.UNAVAILABLE))
        val page = page("d")
        advanceUntilIdle()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.OfficeUnavailable)
        assertThat(repo.requested).isEmpty()
    }

    @Test
    fun `a request answered with unavailable stops without polling`() = runTest {
        repo.respond("d", info("d", PreviewKind.OFFICE, contentUrl = null, status = null))
        repo.requestResult = DerivativeStatus.UNAVAILABLE
        val page = page("d")
        advanceUntilIdle()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.OfficeUnavailable)
        assertThat(repo.previewCalls["d"]).isEqualTo(1)
    }

    @Test
    fun `a failed conversion can be retried`() = runTest {
        repo.respond("d", info("d", PreviewKind.OFFICE, contentUrl = null, status = DerivativeStatus.FAILED))
        val page = page("d")
        advanceUntilIdle()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.OfficeFailed)
        assertThat(repo.requested).isEmpty()

        repo.respond(
            "d",
            info("d", PreviewKind.OFFICE, contentUrl = null, status = DerivativeStatus.RUNNING),
            info("d", PreviewKind.OFFICE, contentUrl = "https://s3/d.pdf", status = DerivativeStatus.DONE),
        )
        page.retryConversion()
        runCurrent()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.OfficeConverting)
        advanceUntilIdle()
        assertThat(repo.requested).containsExactly("d")
        assertThat(page.state.value.content).isInstanceOf(PreviewContent.Pdf::class.java)
    }

    @Test
    fun `a load error can be retried`() = runTest {
        repo.respond("a", info("a", PreviewKind.VIDEO, largeUrl = "https://s3/a-poster"))
        repo.failNextPreview = GopanException(GopanErrors.NETWORK, "网络连接失败")
        val page = page("a")
        advanceUntilIdle()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.Error("网络连接失败"))

        page.retry()
        advanceUntilIdle()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.Video("https://s3/a", "https://s3/a-poster"))
    }

    @Test
    fun `text is fetched and a failed fetch shows an error`() = runTest {
        repo.respond("t", info("t", PreviewKind.TEXT))
        repo.texts["https://s3/t"] = TextContent("你好", truncated = false, charset = "UTF-8")
        val ok = page("t")
        advanceUntilIdle()
        assertThat(ok.state.value.content).isEqualTo(PreviewContent.Text(TextContent("你好", false, "UTF-8")))

        repo.respond("u", info("u", PreviewKind.TEXT))
        val failed = page("u")
        advanceUntilIdle()
        assertThat(failed.state.value.content).isInstanceOf(PreviewContent.Error::class.java)
    }

    @Test
    fun `a long text loads the next segment with the same charset`() = runTest {
        repo.respond("t", info("t", PreviewKind.TEXT))
        repo.texts["https://s3/t"] = TextContent("part1", truncated = true, charset = "GB18030", nextOffset = 5, totalBytes = 10)
        repo.texts["https://s3/t@5"] = TextContent("part2", truncated = false, charset = "GB18030", nextOffset = 10, totalBytes = 10)
        val page = page("t")
        advanceUntilIdle()
        page.loadMoreText()
        assertThat((page.state.value.content as PreviewContent.Text).loadingMore).isTrue()
        advanceUntilIdle()
        val text = page.state.value.content as PreviewContent.Text
        assertThat(text.content.text).isEqualTo("part1part2")
        assertThat(text.content.truncated).isFalse()
        assertThat(text.loadingMore).isFalse()
        assertThat(repo.textCalls.last()).isEqualTo(Triple("https://s3/t", 5L, "GB18030"))
    }

    @Test
    fun `a failed next segment keeps what is shown and says why`() = runTest {
        repo.respond("t", info("t", PreviewKind.TEXT))
        repo.texts["https://s3/t"] = TextContent("part1", truncated = true, charset = "UTF-8", nextOffset = 5, totalBytes = 10)
        val page = page("t")
        advanceUntilIdle()
        page.loadMoreText()
        advanceUntilIdle()
        val text = page.state.value.content as PreviewContent.Text
        assertThat(text.content.text).isEqualTo("part1")
        assertThat(text.moreError).isNotNull()
        // 失败后换过一次地址再试
        assertThat(repo.textCalls.count { it.second == 5L }).isEqualTo(2)
    }

    @Test
    fun `kinds without a preview and missing urls are unsupported`() = runTest {
        repo.respond("n", info("n", PreviewKind.NONE))
        repo.respond("v", info("v", PreviewKind.VIDEO, contentUrl = null))
        val none = page("n")
        val video = page("v")
        advanceUntilIdle()
        assertThat(none.state.value.content).isEqualTo(PreviewContent.Unsupported)
        assertThat(video.state.value.content).isEqualTo(PreviewContent.Unsupported)
    }

    @Test
    fun `a fresh url for expired media updates the info`() = runTest {
        repo.respond(
            "m",
            info("m", PreviewKind.AUDIO, contentUrl = "https://s3/m?sig=1"),
            info("m", PreviewKind.AUDIO, contentUrl = "https://s3/m?sig=2"),
        )
        val page = page("m")
        advanceUntilIdle()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.Audio("https://s3/m?sig=1", null))

        assertThat(page.freshContentUrl()).isEqualTo("https://s3/m?sig=2")
        assertThat(page.state.value.info?.contentUrl).isEqualTo("https://s3/m?sig=2")
    }

    @Test
    fun `an archive shows its summary and polls while the index is being built`() = runTest {
        repo.respond("z", info("z", PreviewKind.ARCHIVE, name = "资料.zip"))
        archives.respondInfo(
            archiveInfo(ArchiveStatus.PENDING),
            archiveInfo(ArchiveStatus.DONE, format = "zip", fileCount = 12, totalSize = 2048),
        )
        val page = page("z")
        runCurrent()
        assertThat(page.state.value.content).isEqualTo(PreviewContent.Archive(ArchiveSummary.Pending))

        advanceTimeBy(PreviewPage.ARCHIVE_POLL_MS)
        runCurrent()
        assertThat(page.state.value.content)
            .isEqualTo(PreviewContent.Archive(ArchiveSummary.Ready("ZIP", 12, 2048, truncated = false, encrypted = false)))
        assertThat(archives.infoCalls).isEqualTo(2)

        // 读完不再问
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(archives.infoCalls).isEqualTo(2)
    }

    @Test
    fun `archive polling pauses while the page is off screen`() = runTest {
        repo.respond("z", info("z", PreviewKind.ARCHIVE))
        archives.respondInfo(archiveInfo(ArchiveStatus.PENDING), archiveInfo(ArchiveStatus.DONE))
        val page = page("z", attached = false)
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(archives.infoCalls).isEqualTo(1)

        page.setAttached(true)
        runCurrent()
        assertThat(archives.infoCalls).isEqualTo(2)
        assertThat(page.state.value.content).isInstanceOf(PreviewContent.Archive::class.java)
    }

    @Test
    fun `an archive the server cannot read, or a failed request, is not a summary`() = runTest {
        repo.respond("u", info("u", PreviewKind.ARCHIVE))
        archives.respondInfo(archiveInfo(ArchiveStatus.FAILED, error = "压缩包损坏"))
        val failed = page("u")
        advanceUntilIdle()
        assertThat(failed.state.value.content).isEqualTo(PreviewContent.Archive(ArchiveSummary.Unavailable("压缩包损坏")))

        archives.infoError = GopanException(GopanErrors.NETWORK, "网络连接失败")
        repo.respond("e", info("e", PreviewKind.ARCHIVE))
        val error = page("e")
        advanceUntilIdle()
        assertThat(error.state.value.content).isEqualTo(PreviewContent.Error("网络连接失败"))
    }
}
