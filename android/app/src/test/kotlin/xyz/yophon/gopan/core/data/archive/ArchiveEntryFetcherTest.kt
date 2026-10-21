package xyz.yophon.gopan.core.data.archive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher
import java.io.File
import java.nio.charset.Charset

class ArchiveEntryFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private var token: String? = "tok"
    private val refreshes = mutableListOf<String?>()
    private var outcome: RefreshOutcome = RefreshOutcome.Refreshed("fresh")

    private val refresher = object : TokenRefresher {
        override suspend fun refreshAfterUnauthenticated(staleToken: String?): RefreshOutcome {
            refreshes += staleToken
            return outcome
        }
    }

    private fun fetcher(dir: File = tmp.root.resolve("cache"), maxBytes: Long = ARCHIVE_ENTRY_MAX_BYTES, cacheBytes: Long = ArchiveEntryFetcher.CACHE_BYTES) =
        ArchiveEntryFetcher(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString(),
            dir = dir,
            accessToken = { token },
            refresher = { refresher },
            maxBytes = maxBytes,
            cacheBytes = cacheBytes,
        )

    private fun entry(idx: Int, name: String, size: Long, previewable: Boolean = true) =
        ArchiveEntry(idx = idx, path = "docs/$name", name = name, isDir = false, size = size, previewable = previewable, downloadable = true)

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `streams an entry into a cache file named after it and reuses it`() = runBlocking {
        server.enqueue(MockResponse().setBody("hello"))
        val f = fetcher()
        val progress = mutableListOf<Pair<Long, Long?>>()
        val file = f.fetch("node-1", entry(3, "报告.txt", 5)) { done, total -> progress += done to total }

        assertThat(file.readText()).isEqualTo("hello")
        assertThat(file.name).isEqualTo("报告.txt")
        assertThat(file.parentFile?.name).isEqualTo("node-1-3")
        assertThat(progress.last()).isEqualTo(5L to 5L)
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/arc/node-1/3")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok")
        // 凭证只走请求头,不进地址
        assertThat(request.requestUrl?.queryParameter("token")).isNull()

        val again = f.fetch("node-1", entry(3, "报告.txt", 5))
        assertThat(again).isEqualTo(file)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `entries that cannot be previewed are fetched as downloads`() = runBlocking {
        server.enqueue(MockResponse().setBody("MZ"))
        fetcher().fetch("node-1", entry(4, "tool.exe", 2, previewable = false))
        assertThat(server.takeRequest().path).isEqualTo("/arc/node-1/4?download=1")
    }

    @Test
    fun `json errors from the server keep their code and chinese message`() {
        server.enqueue(
            MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
                .setBody("""{"code":"ARCHIVE_ENTRY_TOO_DEEP","message":"要先解开前面的 300 MB 才能读到它,请解压后再看"}"""),
        )
        val dir = tmp.root.resolve("cache")
        val e = assertThrows(GopanException::class.java) { runBlocking { fetcher(dir).fetch("node-1", entry(5, "deep.txt", 10)) } }
        assertThat(e.code).isEqualTo(ArchiveErrors.ENTRY_TOO_DEEP)
        assertThat(e.message).isEqualTo("要先解开前面的 300 MB 才能读到它,请解压后再看")
        // 失败不留半个文件
        assertThat(dir.walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `busy is reported with its retry advice`() {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "10")
                .setBody("""{"code":"ARCHIVE_BUSY","message":"同时预览的人太多,稍后再试"}"""),
        )
        val e = assertThrows(GopanException::class.java) { runBlocking { fetcher().fetch("node-1", entry(5, "a.txt", 10)) } }
        assertThat(e.code).isEqualTo(ArchiveErrors.ARCHIVE_BUSY)
        assertThat(e.message).isEqualTo("同时预览的人太多,稍后再试")
    }

    @Test
    fun `an expired token is refreshed once and the request replayed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"UNAUTHENTICATED","message":"未登录或凭证已过期"}"""))
        server.enqueue(MockResponse().setBody("ok"))
        val file = fetcher().fetch("node-1", entry(6, "a.txt", 2))
        assertThat(file.readText()).isEqualTo("ok")
        assertThat(refreshes).containsExactly("tok")
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer fresh")
    }

    @Test
    fun `a refused refresh means signed out`() {
        outcome = RefreshOutcome.SignedOut
        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertThrows(GopanException::class.java) { runBlocking { fetcher().fetch("node-1", entry(6, "a.txt", 2)) } }
        assertThat(e.code).isEqualTo(GopanErrors.UNAUTHENTICATED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `bodies over the size limit are cut off`() {
        server.enqueue(MockResponse().setChunkedBody("0123456789", 3))
        val dir = tmp.root.resolve("cache")
        val e = assertThrows(GopanException::class.java) {
            runBlocking { fetcher(dir, maxBytes = 4).fetch("node-1", entry(7, "a.bin", 4)) }
        }
        assertThat(e.code).isEqualTo(ArchiveErrors.ENTRY_TOO_LARGE)
        assertThat(dir.walkTopDown().filter { it.isFile }.toList()).isEmpty()
        // 声明就超过的不发请求
        val e2 = assertThrows(GopanException::class.java) {
            runBlocking { fetcher(dir, maxBytes = 4).fetch("node-1", entry(8, "b.bin", 5)) }
        }
        assertThat(e2.code).isEqualTo(ArchiveErrors.ENTRY_TOO_LARGE)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `the cache drops the least recently used entries and release clears one archive`() = runBlocking {
        val dir = tmp.root.resolve("cache")
        val f = fetcher(dir, cacheBytes = 8)
        server.enqueue(MockResponse().setBody("aaaaa"))
        server.enqueue(MockResponse().setBody("bbbbb"))
        server.enqueue(MockResponse().setBody("cc"))
        f.fetch("node-1", entry(1, "a.txt", 5))
        File(dir, "node-1-1").setLastModified(1_000)
        f.fetch("node-1", entry(2, "b.txt", 5))
        // 超过 8 字节:最久没用的 node-1-1 被删,刚下好的留着
        assertThat(File(dir, "node-1-1").exists()).isFalse()
        assertThat(File(dir, "node-1-2/b.txt").readText()).isEqualTo("bbbbb")

        f.fetch("node-2", entry(1, "c.txt", 2))
        f.release("node-1")
        assertThat(File(dir, "node-1-2").exists()).isFalse()
        assertThat(File(dir, "node-2-1/c.txt").exists()).isTrue()

        f.clearAll()
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `arc errors without json fall back to the status`() {
        val bad = arcError(502, "<html>Bad Gateway</html>")
        assertThat(bad.code).isEqualTo(GopanErrors.HTTP)
        assertThat(bad.message).isEqualTo("加载失败(HTTP 502)")
        assertThat(arcError(404, null).code).isEqualTo(GopanErrors.NOT_FOUND)
        assertThat(arcError(403, "").code).isEqualTo(GopanErrors.FORBIDDEN)
        assertThat(arcError(400, """{"code":["x"]}""").code).isEqualTo(GopanErrors.HTTP)
        val unsupported = arcError(415, """{"code":"UNSUPPORTED_TYPE","message":"压缩包里只能直接预览图片、PDF 和文本"}""")
        assertThat(unsupported.code).isEqualTo(ArchiveErrors.UNSUPPORTED_TYPE)
        assertThat(unsupported.message).isEqualTo("压缩包里只能直接预览图片、PDF 和文本")
        assertThat(unsupported.serverMessage).isEqualTo("压缩包里只能直接预览图片、PDF 和文本")
    }

    @Test
    fun `entry urls carry no credentials`() {
        assertThat(archiveEntryUrl("https://drive.example/", "n1", 3, download = false).toString()).isEqualTo("https://drive.example/arc/n1/3")
        assertThat(archiveEntryUrl("https://drive.example", "n1", 3, download = true).toString()).isEqualTo("https://drive.example/arc/n1/3?download=1")
    }

    @Test
    fun `file names are made safe for the file system`() {
        assertThat(ArchiveEntryFetcher.safeFileName("a/b\\c:d*e?.txt")).isEqualTo("a_b_c_d_e_.txt")
        assertThat(ArchiveEntryFetcher.safeFileName("..")).isEqualTo("file")
        val long = "很".repeat(200) + ".pdf"
        val safe = ArchiveEntryFetcher.safeFileName(long)
        assertThat(safe.length).isAtMost(120)
        assertThat(safe).endsWith(".pdf")
    }

    @Test
    fun `local text is read in segments with the encoding of the first`() {
        val gbk = Charset.forName("GB18030")
        // 每个汉字 2 字节,共 10 字节;第一段 7 字节在「文」的中间截断
        val file = tmp.newFile("gbk.txt").apply { writeBytes("中文中文ab".toByteArray(gbk)) }
        val first = readTextSegment(file, maxBytes = 7)
        assertThat(first.text).isEqualTo("中文中")
        assertThat(first.charset).isEqualTo("GB18030")
        assertThat(first.truncated).isTrue()
        assertThat(first.nextOffset).isEqualTo(6)
        assertThat(first.totalBytes).isEqualTo(10)
        val second = readTextSegment(file, first.nextOffset, first.charset, maxBytes = 10)
        assertThat(second.text).isEqualTo("文ab")
        assertThat(second.truncated).isFalse()

        val utf8 = tmp.newFile("bom.txt").apply { writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()) }
        assertThat(readTextSegment(utf8).text).isEqualTo("hi")
    }
}
