package xyz.yophon.gopan.feature.preview

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.preview.TextContent
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.offline.LocalCopy
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository
import java.io.File

/** 离线副本(doc/26):没网用副本;有网且内容没变,原文件从副本读;内容变了看云端的 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewOfflineTest {

    private val repo = FakePreviewRepository()
    private val offline = FakeOfflineLookup()
    private var pageScope: CoroutineScope? = null

    private fun TestScope.page(id: String): PreviewPage {
        val scope = pageScope ?: CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { pageScope = it }
        return PreviewPage(id, repo, scope, FakeArchiveRepository(), offline).also {
            it.setAttached(true)
            it.start()
        }
    }

    @After
    fun tearDown() {
        pageScope?.cancel()
    }

    private fun copy(id: String, name: String, sha: String? = "sha-$id") =
        LocalCopy(id, name, 1234, sha, null, File("/offline/root/$id/$name"))

    private val File.uriString: String get() = toURI().toString()

    @Test
    fun `offline the page is built from the copy without asking the server`() = runTest {
        offline.online = false
        val c = copy("p", "照片.jpg")
        offline.copies["p"] = c
        val page = page("p")
        advanceUntilIdle()

        assertThat(repo.previewCalls["p"]).isNull()
        assertThat(page.state.value.info?.kind).isEqualTo(PreviewKind.IMAGE)
        assertThat(page.state.value.content).isEqualTo(PreviewContent.Image(c.file.uriString, "sha-p#original", original = true))
    }

    @Test
    fun `online with the same content the original comes from the copy`() = runTest {
        val c = copy("v", "片.mp4")
        offline.copies["v"] = c
        repo.respond("v", info("v", PreviewKind.VIDEO, largeUrl = "https://s3/v-poster"))
        val page = page("v")
        advanceUntilIdle()

        assertThat(page.state.value.content).isEqualTo(PreviewContent.Video(c.file.uriString, "https://s3/v-poster"))
    }

    @Test
    fun `online with changed content the cloud version is shown`() = runTest {
        offline.copies["i"] = copy("i", "图.png", sha = "old")
        repo.respond("i", info("i", PreviewKind.IMAGE, largeUrl = "https://s3/i-large"))
        val page = page("i")
        advanceUntilIdle()

        assertThat(page.state.value.content).isEqualTo(PreviewContent.Image("https://s3/i-large", "sha-i#large", original = false))
    }

    @Test
    fun `when the server cannot be reached the copy is used, markdown as plain text`() = runTest {
        val c = copy("m", "纪要.md")
        offline.copies["m"] = c
        repo.failNextPreview = GopanException(GopanErrors.NETWORK, "网络不可用")
        repo.texts[c.file.uriString] = TextContent("# 纪要", truncated = false, charset = "UTF-8")
        val page = page("m")
        advanceUntilIdle()

        assertThat(page.state.value.content).isEqualTo(PreviewContent.Text(TextContent("# 纪要", truncated = false, charset = "UTF-8")))
    }

    @Test
    fun `offline documents and archives fall back to opening with another app`() = runTest {
        offline.online = false
        offline.copies["d"] = copy("d", "报告.docx")
        offline.copies["z"] = copy("z", "包.zip")
        val doc = page("d")
        val zip = page("z")
        advanceUntilIdle()

        assertThat(doc.state.value.content).isEqualTo(PreviewContent.Unsupported)
        assertThat(zip.state.value.content).isEqualTo(PreviewContent.Unsupported)
    }

    @Test
    fun `without a copy a failure is shown as before`() = runTest {
        repo.failNextPreview = GopanException(GopanErrors.NETWORK, "网络不可用")
        val page = page("x")
        advanceUntilIdle()

        assertThat(page.state.value.content).isEqualTo(PreviewContent.Error("网络不可用"))
    }
}
