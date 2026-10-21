package xyz.yophon.gopan.core.data.jobs

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.jobs.FakeJobsRepository.Companion.job
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import java.io.IOException
import java.time.Instant

/** 与网页端 web/src/utils/jobs.test.ts 同一批用例,另加 App 自己的几条 */
class JobsLogicTest {

    private val base = job("j", JobStatus.RUNNING)

    // ---- parseUrls ----

    @Test
    fun `parseUrls keeps http(s) only, one per line, deduped in order`() {
        assertThat(parseUrls("https://a.com/x.zip\nhttp://b.com/y\nftp://c.com/z\nhttps://a.com/x.zip\n\n  not a url"))
            .containsExactly("https://a.com/x.zip", "http://b.com/y").inOrder()
    }

    @Test
    fun `parseUrls picks links glued to Chinese text and strips surrounding punctuation`() {
        assertThat(parseUrls("看看这个「https://example.com/报告.pdf」,")).containsExactly("https://example.com/报告.pdf")
        assertThat(parseUrls("【会议纪要】https://example.com/m.docx 点开看")).containsExactly("https://example.com/m.docx")
        assertThat(parseUrls("看看这个 <https://example.com/a.pdf>。")).containsExactly("https://example.com/a.pdf")
        assertThat(parseUrls("报告 https://example.com/b.pdf，")).containsExactly("https://example.com/b.pdf")
        assertThat(parseUrls("（https://example.com/c.pdf）")).containsExactly("https://example.com/c.pdf")
        assertThat(parseUrls("(https://example.com/d.pdf).")).containsExactly("https://example.com/d.pdf")
    }

    @Test
    fun `parseUrls splits links joined by full-width punctuation or spaces`() {
        assertThat(parseUrls("链接1：https://a.com/1.zip，链接2：https://b.com/2.zip；完"))
            .containsExactly("https://a.com/1.zip", "https://b.com/2.zip").inOrder()
        assertThat(parseUrls("https://a.com/1.zip　https://b.com/2.zip！"))
            .containsExactly("https://a.com/1.zip", "https://b.com/2.zip").inOrder()
        assertThat(parseUrls("下载 https://a.com/x.iso？还有 https://b.com/y.iso、"))
            .containsExactly("https://a.com/x.iso", "https://b.com/y.iso").inOrder()
        // 英文标点紧跟中文:标点不算链接
        assertThat(parseUrls("【报告】https://a.com/x.pdf,还有 https://b.com/y.zip;完"))
            .containsExactly("https://a.com/x.pdf", "https://b.com/y.zip").inOrder()
        assertThat(parseUrls("https://a.com/x.iso?下载")).containsExactly("https://a.com/x.iso")
    }

    @Test
    fun `parseUrls keeps commas and question marks inside links`() {
        assertThat(parseUrls("https://a.com/get?ids=1,2&x=y!z")).containsExactly("https://a.com/get?ids=1,2&x=y!z")
        assertThat(parseUrls("https://a.com/get?ids=1,2, 然后")).containsExactly("https://a.com/get?ids=1,2")
        // 网页存档的链接里还嵌着一个 https://,不拆
        assertThat(parseUrls("https://web.archive.org/web/2020/https://example.com/a.pdf"))
            .containsExactly("https://web.archive.org/web/2020/https://example.com/a.pdf")
        // 中文域名、中文路径
        assertThat(parseUrls("https://例子.中国/报告.pdf")).containsExactly("https://例子.中国/报告.pdf")
    }

    @Test
    fun `parseUrls keeps query strings, ports and case-insensitive schemes`() {
        assertThat(parseUrls("HTTPS://Example.com:8443/a.zip?sig=abc&e=1#frag"))
            .containsExactly("HTTPS://Example.com:8443/a.zip?sig=abc&e=1#frag")
        assertThat(parseUrls("https://example.com/file.zip...")).containsExactly("https://example.com/file.zip")
    }

    @Test
    fun `parseUrls ignores bare schemes and text without links`() {
        assertThat(parseUrls("")).isEmpty()
        assertThat(parseUrls("没有链接的一段话")).isEmpty()
        assertThat(parseUrls("https:// 和 http://")).isEmpty()
    }

    @Test
    fun `parseUrls returns every link so the caller can enforce the cap`() {
        val text = (1..12).joinToString("\n") { "https://example.com/$it.zip" }
        val urls = parseUrls(text)
        assertThat(urls).hasSize(12)
        assertThat(urls.size > MAX_FETCH_URLS).isTrue()
    }

    // ---- jobPercent ----

    @Test
    fun `jobPercent uses bytes when the total is known, null otherwise`() {
        assertThat(jobPercent(base.copy(bytesDone = 50, bytesTotal = 200))).isEqualTo(25)
        assertThat(jobPercent(base.copy(bytesDone = 50))).isNull()
        assertThat(jobPercent(base.copy(bytesDone = 50, bytesTotal = 0))).isNull()
        assertThat(jobPercent(base.copy(status = JobStatus.DONE))).isEqualTo(100)
        assertThat(jobPercent(base.copy(bytesDone = 300, bytesTotal = 200))).isEqualTo(100)
    }

    @Test
    fun `jobPercent counts files for extraction`() {
        assertThat(jobPercent(base.copy(kind = JobKind.EXTRACT_ARCHIVE, filesDone = 3, filesTotal = 4))).isEqualTo(75)
        assertThat(jobPercent(base.copy(kind = JobKind.EXTRACT_ARCHIVE, filesDone = 3))).isNull()
    }

    // ---- jobStatusText ----

    @Test
    fun `jobStatusText matches the web copy for every state`() {
        assertThat(jobStatusText(base.copy(status = JobStatus.QUEUED))).isEqualTo("排队中")
        assertThat(jobStatusText(base.copy(status = JobStatus.QUEUED, errorMessage = "连接源站超时"))).isEqualTo("等待重试:连接源站超时")
        assertThat(jobStatusText(base.copy(bytesDone = 1024, bytesTotal = 2048), 512)).isEqualTo("1.0 KB / 2.0 KB · 512 B/s")
        assertThat(jobStatusText(base.copy(bytesDone = 1024))).isEqualTo("1.0 KB")
        assertThat(jobStatusText(base.copy(bytesDone = 1024), 0)).isEqualTo("1.0 KB")
        assertThat(jobStatusText(base.copy(status = JobStatus.DONE, bytesDone = 2048))).isEqualTo("已完成 · 2.0 KB")
        assertThat(jobStatusText(base.copy(status = JobStatus.DONE, errorCode = GOT_HTML))).contains("网页")
        assertThat(jobStatusText(base.copy(status = JobStatus.FAILED, errorMessage = "源站返回 HTTP 404"))).isEqualTo("源站返回 HTTP 404")
        assertThat(jobStatusText(base.copy(status = JobStatus.FAILED))).isEqualTo("失败")
        assertThat(jobStatusText(base.copy(status = JobStatus.CANCELED))).isEqualTo("已取消")
    }

    @Test
    fun `jobStatusText for extraction`() {
        val x = base.copy(kind = JobKind.EXTRACT_ARCHIVE, url = null)
        assertThat(jobStatusText(x.copy(status = JobStatus.DONE, filesDone = 9, filesSkipped = 1))).isEqualTo("已解压 9 个文件,跳过 1 个")
        assertThat(jobStatusText(x.copy(status = JobStatus.DONE, filesDone = 9))).isEqualTo("已解压 9 个文件")
        assertThat(jobStatusText(x.copy(filesDone = 2, filesTotal = 5))).isEqualTo("解压中 2 / 5 个文件")
        assertThat(jobStatusText(x.copy(filesDone = 2))).isEqualTo("解压中 2 个文件")
    }

    @Test
    fun `active, warning and retryable flags`() {
        assertThat(base.copy(status = JobStatus.QUEUED).isActive).isTrue()
        assertThat(base.isActive).isTrue()
        assertThat(base.copy(status = JobStatus.DONE).isActive).isFalse()
        assertThat(base.copy(status = JobStatus.DONE, errorCode = GOT_HTML).isWarning).isTrue()
        assertThat(base.copy(status = JobStatus.DONE).isWarning).isFalse()
        assertThat(base.copy(status = JobStatus.FAILED, errorCode = "HTTP_404").isWarning).isFalse()
        assertThat(base.copy(status = JobStatus.FAILED).isRetryable).isTrue()
        assertThat(base.copy(status = JobStatus.CANCELED).isRetryable).isTrue()
        assertThat(base.copy(status = JobStatus.DONE).isRetryable).isFalse()
    }

    // ---- jobWorthShowing ----

    @Test
    fun `jobWorthShowing keeps active, recently finished and day-old failures`() {
        val now = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()
        assertThat(jobWorthShowing(base, now)).isTrue()
        assertThat(jobWorthShowing(base.copy(status = JobStatus.QUEUED), now)).isTrue()
        assertThat(jobWorthShowing(base.copy(status = JobStatus.DONE, finishedAt = "2026-10-21T11:55:00Z"), now)).isTrue()
        assertThat(jobWorthShowing(base.copy(status = JobStatus.DONE, finishedAt = "2026-10-21T11:00:00Z"), now)).isFalse()
        assertThat(jobWorthShowing(base.copy(status = JobStatus.FAILED, finishedAt = "2026-10-21T01:00:00Z"), now)).isTrue()
        assertThat(jobWorthShowing(base.copy(status = JobStatus.FAILED, finishedAt = "2026-10-19T01:00:00Z"), now)).isFalse()
        assertThat(jobWorthShowing(base.copy(status = JobStatus.CANCELED, finishedAt = null), now)).isFalse()
        // 服务端带小数秒与时区偏移的时间也认
        assertThat(jobWorthShowing(base.copy(status = JobStatus.CANCELED, finishedAt = "2026-10-21T19:58:30.123456+08:00"), now)).isTrue()
    }

    // ---- 打开所在位置 ----

    @Test
    fun `jobLocation opens the file's folder or the extracted folder`() {
        val file = JobResult(id = "f1", name = "a.zip", isFolder = false, parentId = "dir")
        assertThat(jobLocation(base.copy(status = JobStatus.DONE, result = file))).isEqualTo(FolderTarget("dir"))
        assertThat(jobLocation(base.copy(status = JobStatus.DONE, result = file.copy(parentId = null)))).isEqualTo(FolderTarget(null))
        val folder = JobResult(id = "x1", name = "a", isFolder = true, parentId = "dir")
        assertThat(jobLocation(base.copy(kind = JobKind.EXTRACT_ARCHIVE, status = JobStatus.DONE, result = folder))).isEqualTo(FolderTarget("x1"))
        assertThat(jobLocation(base.copy(status = JobStatus.DONE, result = null))).isNull()
        assertThat(jobLocation(base.copy(result = file))).isNull()
    }

    // ---- 速度与刚完成 ----

    @Test
    fun `speeds come from the byte delta between two running samples`() {
        val prev = mapOf(
            "a" to JobSample(JobStatus.RUNNING, 1_000, 10_000),
            "b" to JobSample(JobStatus.QUEUED, 0, 10_000),
            "c" to JobSample(JobStatus.RUNNING, 5_000, 10_000),
        )
        val jobs = listOf(
            job("a", JobStatus.RUNNING, bytesDone = 5_000),
            job("b", JobStatus.RUNNING, bytesDone = 2_000),
            // 断线重来:字节变少不算负速度
            job("c", JobStatus.RUNNING, bytesDone = 0),
            job("d", JobStatus.RUNNING, bytesDone = 9_000),
        )
        assertThat(jobSpeeds(prev, jobs, 12_000)).containsExactly("a", 2_000L, "c", 0L)
        assertThat(jobSpeeds(prev, jobs, 10_000)).isEmpty()
    }

    @Test
    fun `justFinished reports jobs that turned done since the last sample`() {
        val prev = jobSamples(listOf(job("a", JobStatus.RUNNING), job("b", JobStatus.QUEUED), job("c", JobStatus.DONE)), 0)
        val now = listOf(
            job("a", JobStatus.DONE),
            job("b", JobStatus.FAILED),
            job("c", JobStatus.DONE),
            job("d", JobStatus.DONE),
        )
        assertThat(justFinished(prev, now).map { it.id }).containsExactly("a")
        assertThat(justFinished(emptyMap(), now)).isEmpty()
    }

    // ---- 错误文案 ----

    @Test
    fun `error text prefers the Chinese server message, then the code table`() {
        assertThat(JobErrors.messageFor(GopanException("TOO_MANY_JOBS", "x", serverMessage = "排队中的后台任务过多(最多 10 个),等前面的完成再来")))
            .isEqualTo("排队中的后台任务过多(最多 10 个),等前面的完成再来")
        assertThat(JobErrors.messageFor(GopanException("TOO_MANY_JOBS", "请求失败(TOO_MANY_JOBS)", serverMessage = "too many jobs")))
            .isEqualTo("排队中的后台任务过多,等前面的完成再来")
        assertThat(JobErrors.messageFor(GopanException("FETCH_DISABLED", "请求失败(FETCH_DISABLED)"))).isEqualTo("本站没有开启离线下载")
        assertThat(JobErrors.messageFor(GopanException("INVALID_URL", "x", serverMessage = "invalid"))).isEqualTo("链接格式不对,只支持 http / https")
        assertThat(JobErrors.messageFor(GopanException("JOB_FINISHED", "x"))).isEqualTo("任务已经结束")
        assertThat(JobErrors.messageFor(GopanException("JOB_NOT_RETRYABLE", "x"))).isEqualTo("只有失败或已取消的任务能重试")
        assertThat(JobErrors.messageFor(GopanException("NOT_A_FOLDER", "x"))).isEqualTo("目标不是文件夹")
        // 不认识的码:用 GopanException 自己的文案
        assertThat(JobErrors.messageFor(GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")))
            .isEqualTo("网络连接失败,请检查网络后重试")
        assertThat(JobErrors.messageFor(IOException("boom"))).isEqualTo("网络连接失败,请检查网络后重试")
    }
}
