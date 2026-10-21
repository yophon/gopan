package xyz.yophon.gopan.core.data.media

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

@OptIn(ExperimentalCoroutinesApi::class)
class PlayProgressReporterTest {

    private val repo = FakeMediaRepository()
    private val reporter = PlayProgressReporter(repo, CoroutineScope(UnconfinedTestDispatcher()))

    private val tenMinutes = 10 * 60 * 1000L

    @Test
    fun `media progress goes up in seconds`() {
        reporter.reportMedia("v", 1_395_500, tenMinutes * 10)
        assertThat(repo.saved).containsExactly(Triple("v", 1395.5, 6000.0))
    }

    @Test
    fun `short media and short pdfs are not worth a request`() {
        // 服务端只记 5 分钟以上的音视频、3 页以上的 PDF
        reporter.reportMedia("song", 60_000, ProgressRules.MIN_MEDIA_MS - 1)
        reporter.reportMedia("unknown", 60_000, 0)
        reporter.reportPdf("memo", 2, 2)
        assertThat(repo.saved).isEmpty()
        reporter.reportMedia("talk", 60_000, ProgressRules.MIN_MEDIA_MS)
        reporter.reportPdf("book", 2, 3)
        assertThat(repo.saved.map { it.first }).containsExactly("talk", "book").inOrder()
    }

    @Test
    fun `the same second is reported once`() {
        reporter.reportMedia("v", 30_200, tenMinutes)
        reporter.reportMedia("v", 30_400, tenMinutes)
        reporter.reportMedia("w", 30_400, tenMinutes)
        reporter.reportMedia("v", 45_000, tenMinutes)
        reporter.reportMedia("v", 30_000, tenMinutes)
        assertThat(repo.saved.map { it.first to it.second }).containsExactly("v" to 30.2, "w" to 30.4, "v" to 45.0, "v" to 30.0).inOrder()
    }

    @Test
    fun `positions are clamped into the media and the document`() {
        reporter.reportMedia("v", -5, tenMinutes)
        reporter.reportMedia("w", tenMinutes + 9_000, tenMinutes)
        reporter.reportPdf("p", 0, 300)
        reporter.reportPdf("q", 301, 300)
        assertThat(repo.saved).containsExactly(
            Triple("v", 0.0, 600.0),
            Triple("w", 600.0, 600.0),
            Triple("p", 1.0, 300.0),
            Triple("q", 300.0, 300.0),
        ).inOrder()
    }

    @Test
    fun `a failed report is swallowed`() {
        repo.saveError = GopanException(GopanErrors.NETWORK, "网络连接失败")
        reporter.reportMedia("v", 30_000, tenMinutes)
        reporter.reportPdf("p", 3, 30)
        assertThat(repo.saved).hasSize(2)
    }
}
