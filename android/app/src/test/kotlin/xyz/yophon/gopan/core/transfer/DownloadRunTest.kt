package xyz.yophon.gopan.core.transfer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import xyz.yophon.gopan.core.transfer.download.DownloadApi
import xyz.yophon.gopan.core.transfer.download.DownloadOutcome
import xyz.yophon.gopan.core.transfer.download.DownloadPublisher
import xyz.yophon.gopan.core.transfer.download.DownloadRun
import xyz.yophon.gopan.core.transfer.download.Fetch
import xyz.yophon.gopan.core.transfer.download.FileTarget
import xyz.yophon.gopan.core.transfer.download.PackOpen
import xyz.yophon.gopan.core.transfer.download.RangeSource
import xyz.yophon.gopan.core.transfer.download.Resolve
import xyz.yophon.gopan.core.transfer.download.TreeItem
import xyz.yophon.gopan.core.transfer.download.TreePage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadRunTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val api = FakeDownloadApi(clock)
    private val storage = FakeObjectStore(api)
    private val publisher = FakePublisher()
    private val expected = mutableListOf<String?>()
    private var freeSpace = Long.MAX_VALUE

    private fun partsDir(): File = File(tmp.root, "parts")

    private fun run(task: DownloadTaskEntity) = DownloadRun(
        api = api,
        fetcher = storage,
        publisher = publisher,
        writer = { _, sha, _, _ -> expected += sha },
        clock = clock,
        partsDir = partsDir(),
        report = {},
        task = task,
        sleep = { clock.advance(it) },
        usableSpace = { freeSpace },
    )

    private fun fileTask(nodeId: String = "n1", sha: String? = null, size: Long? = null, dest: DownloadDest = DownloadDest.DOWNLOADS) =
        DownloadTaskEntity(id = 7, userId = "u1", kind = DownloadKind.FILE, nodeId = nodeId, name = "a.bin", size = size, sha256 = sha, dest = dest, createdAt = 0, updatedAt = 0)

    @Test
    fun `downloads, verifies and publishes a file`() = runTest {
        val bytes = randomBytes(5_000)
        api.file("n1", "a.bin", bytes)

        val outcome = run(fileTask()).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(publisher.published.single().second).isEqualTo(bytes)
        assertThat(expected).containsExactly(sha256Hex(bytes))
        assertThat(File(partsDir(), "7.part").exists()).isFalse()
    }

    @Test
    fun `resumes from the existing part with a range request`() = runTest {
        val bytes = randomBytes(5_000)
        api.file("n1", "a.bin", bytes)
        partsDir().mkdirs()
        File(partsDir(), "7.part").writeBytes(bytes.copyOfRange(0, 2_000))

        val outcome = run(fileTask(sha = sha256Hex(bytes), size = 5_000)).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(storage.requests.single().second).isEqualTo(2_000)
        assertThat(publisher.published.single().second).isEqualTo(bytes)
    }

    @Test
    fun `server ignoring the range restarts the part from zero`() = runTest {
        val bytes = randomBytes(5_000)
        api.file("n1", "a.bin", bytes)
        storage.ignoreRange = true
        partsDir().mkdirs()
        File(partsDir(), "7.part").writeBytes(bytes.copyOfRange(0, 1_000))

        val outcome = run(fileTask(sha = sha256Hex(bytes), size = 5_000)).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(publisher.published.single().second).isEqualTo(bytes)
    }

    @Test
    fun `connection dropped mid-body resumes where it stopped`() = runTest {
        val bytes = randomBytes(600_000)
        api.file("n1", "a.bin", bytes)
        storage.truncateFirstBodyAt = 250_000

        val outcome = run(fileTask()).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(storage.requests.map { it.second }).containsExactly(0L, 250_000L).inOrder()
        assertThat(publisher.published.single().second).isEqualTo(bytes)
    }

    @Test
    fun `403 refreshes the url once`() = runTest {
        val bytes = randomBytes(3_000)
        api.file("n1", "a.bin", bytes)
        storage.queue += Fetch.Forbidden

        val outcome = run(fileTask()).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(api.resolveCalls).isEqualTo(2)
    }

    @Test
    fun `403 twice is a signature failure`() = runTest {
        api.file("n1", "a.bin", randomBytes(3_000))
        storage.queue += listOf(Fetch.Forbidden, Fetch.Forbidden)

        val outcome = run(fileTask()).execute()

        assertThat((outcome as DownloadOutcome.Failed).error.code).isEqualTo(TransferErrors.STORAGE_SIGNATURE)
    }

    @Test
    fun `overwritten content discards the part and downloads the new bytes`() = runTest {
        val old = randomBytes(4_000, seed = 1)
        val new = randomBytes(4_500, seed = 2)
        api.file("n1", "a.bin", new)
        partsDir().mkdirs()
        File(partsDir(), "7.part").writeBytes(old.copyOfRange(0, 3_000))

        val outcome = run(fileTask(sha = sha256Hex(old), size = 4_000)).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(storage.requests.first().second).isEqualTo(0)
        assertThat(publisher.published.single().second).isEqualTo(new)
        assertThat(expected).containsExactly(sha256Hex(new))
    }

    @Test
    fun `checksum mismatch twice fails`() = runTest {
        val bytes = randomBytes(3_000)
        api.file("n1", "a.bin", bytes)
        storage.corrupt = true

        val outcome = run(fileTask()).execute()

        assertThat((outcome as DownloadOutcome.Failed).error.code).isEqualTo(TransferErrors.CHECKSUM)
        assertThat(storage.requests).hasSize(2)
    }

    @Test
    fun `deleted file fails as gone`() = runTest {
        val outcome = run(fileTask(nodeId = "missing")).execute()

        assertThat((outcome as DownloadOutcome.Failed).error.code).isEqualTo(TransferErrors.GONE)
    }

    @Test
    fun `transient errors back off and recover`() = runTest {
        val bytes = randomBytes(3_000)
        api.file("n1", "a.bin", bytes)
        storage.queue += listOf(Fetch.Transient(IOException("x")), Fetch.Transient(null, 503), Fetch.Transient(null, 502))

        val outcome = run(fileTask()).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(clock.nowMs).isGreaterThan(1_700_000_000_000L)
    }

    @Test
    fun `persistent network errors become retryable`() = runTest {
        api.file("n1", "a.bin", randomBytes(3_000))
        repeat(10) { storage.queue += Fetch.Transient(IOException("x")) }

        val outcome = run(fileTask()).execute()

        assertThat((outcome as DownloadOutcome.Retry).error.code).isEqualTo(GopanErrors.NETWORK)
    }

    @Test
    fun `stale url is re-resolved before reconnecting`() = runTest {
        val bytes = randomBytes(600_000)
        api.file("n1", "a.bin", bytes)
        storage.truncateFirstBodyAt = 100_000
        storage.afterFirstRequest = { clock.advance(11 * 60_000L) }

        val outcome = run(fileTask()).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(api.resolveCalls).isEqualTo(2)
    }

    @Test
    fun `not enough space fails before downloading`() = runTest {
        api.file("n1", "a.bin", randomBytes(3_000))
        freeSpace = 10

        val outcome = run(fileTask()).execute()

        assertThat((outcome as DownloadOutcome.Failed).error.code).isEqualTo(TransferErrors.NO_SPACE)
        assertThat(storage.requests).isEmpty()
    }

    @Test
    fun `open with reuses the cached copy`() = runTest {
        val bytes = randomBytes(3_000)
        api.file("n1", "a.bin", bytes)
        publisher.cached = File(tmp.root, "cached.bin").apply { writeBytes(bytes) }

        val outcome = run(fileTask(dest = DownloadDest.OPEN_WITH)).execute()

        assertThat((outcome as DownloadOutcome.Done).location).isEqualTo(publisher.cached!!.absolutePath)
        assertThat(storage.requests).isEmpty()
    }

    @Test
    fun `folder expands breadth first with sanitized, unique names`() = runTest {
        api.folder("root", "相册", listOf(TreeItem("f1", "a:b.jpg", false, 10), TreeItem("f2", "a_b.jpg", false, 20), TreeItem("sub", "子目录", true, null)))
        api.folder("sub", "子目录", listOf(TreeItem("f3", "c.txt", false, 5)))
        api.pageSize = 2
        val task = DownloadTaskEntity(id = 9, userId = "u1", kind = DownloadKind.FOLDER, nodeId = "root", name = "相册", dest = DownloadDest.DOWNLOADS, createdAt = 0, updatedAt = 0)

        val outcome = run(task).execute() as DownloadOutcome.Expanded

        assertThat(outcome.totalBytes).isEqualTo(35)
        assertThat(outcome.children.map { it.relDir to it.name })
            .containsExactly("相册" to "a_b.jpg", "相册" to "a_b (1).jpg", "相册/子目录" to "c.txt").inOrder()
        assertThat(outcome.children.map { it.batchId }.toSet()).containsExactly(9L)
    }

    private fun packTask(size: Long = 100) =
        DownloadTaskEntity(id = 11, userId = "u1", kind = DownloadKind.PACK, packNodeIds = "a,b", name = "gopan-打包.zip", size = size, dest = DownloadDest.DOWNLOADS, createdAt = 0, updatedAt = 0)

    @Test
    fun `pack downloads and validates the zip`() = runTest {
        api.pack = PackOpen.Stream(zipOf("a.txt" to "hello", "b/c.txt" to "world").toResponseBody())

        val outcome = run(packTask()).execute()

        assertThat(outcome).isInstanceOf(DownloadOutcome.Done::class.java)
        assertThat(publisher.published.single().first).isEqualTo("gopan-打包.zip")
    }

    @Test
    fun `truncated pack is retried`() = runTest {
        val zip = zipOf("a.txt" to "hello world ".repeat(100))
        api.pack = PackOpen.Stream(zip.copyOfRange(0, zip.size - 30).toResponseBody())

        val outcome = run(packTask()).execute()

        assertThat((outcome as DownloadOutcome.Retry).error.code).isEqualTo(TransferErrors.PACK_CORRUPT)
        assertThat(publisher.published).isEmpty()
    }

    @Test
    fun `pack rate limit waits for retry-after and too large fails`() = runTest {
        api.pack = PackOpen.RetryLater(30)
        assertThat((run(packTask()).execute() as DownloadOutcome.Retry).minDelayMs).isEqualTo(30_000)

        api.pack = PackOpen.Rejected(413, "打包内容超过 2GB 上限")
        assertThat((run(packTask()).execute() as DownloadOutcome.Failed).error.code).isEqualTo(TransferErrors.PACK_TOO_LARGE)
    }

    @Test
    fun `content range header parsing`() {
        assertThat(xyz.yophon.gopan.core.transfer.download.RangeFetcher.parseContentRange("bytes 100-199/1000")).isEqualTo(100L to 1000L)
        assertThat(xyz.yophon.gopan.core.transfer.download.RangeFetcher.parseContentRange("bytes 0-0/*")).isEqualTo(0L to null)
        assertThat(xyz.yophon.gopan.core.transfer.download.RangeFetcher.parseContentRange("items 1-2/3")).isNull()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

class FakeDownloadApi(private val clock: FakeClock) : DownloadApi {
    val files = mutableMapOf<String, Triple<String, ByteArray, String>>()
    val folders = mutableMapOf<String, List<TreeItem>>()
    var pageSize = 200
    var resolveCalls = 0
    var pack: PackOpen = PackOpen.Rejected(500, null)

    fun file(id: String, name: String, bytes: ByteArray) {
        files[id] = Triple(name, bytes, sha256Hex(bytes))
    }

    fun folder(id: String, @Suppress("UNUSED_PARAMETER") name: String, children: List<TreeItem>) {
        folders[id] = children
    }

    override suspend fun resolve(nodeId: String): Resolve {
        resolveCalls++
        if (nodeId in folders) return Resolve.NotAFile
        val (name, bytes, sha) = files[nodeId] ?: return Resolve.Gone
        return Resolve.Ok(FileTarget(nodeId, name, bytes.size.toLong(), sha, "application/octet-stream", "https://dl.test/$nodeId?r=$resolveCalls", clock.elapsed()))
    }

    override suspend fun listChildren(folderId: String, cursor: String?): TreePage {
        val all = folders[folderId] ?: throw GopanException(GopanErrors.NOT_FOUND, "对象不存在")
        val start = cursor?.toInt() ?: 0
        val end = minOf(all.size, start + pageSize)
        return TreePage(all.subList(start, end), if (end < all.size) end.toString() else null)
    }

    override suspend fun openPack(nodeIds: List<String>): PackOpen = pack
}

/** 预签名 GET 的内存实现:支持 Range、注入失败、忽略 Range、首个响应截断、内容损坏 */
class FakeObjectStore(private val api: FakeDownloadApi) : RangeSource {
    val requests = mutableListOf<Pair<String, Long>>()
    val queue = ArrayDeque<Fetch>()
    var ignoreRange = false
    var corrupt = false
    var truncateFirstBodyAt: Int? = null
    var afterFirstRequest: (() -> Unit)? = null

    override suspend fun open(url: String, from: Long): Fetch {
        queue.removeFirstOrNull()?.let { return it }
        requests += url to from
        if (requests.size == 1) afterFirstRequest?.invoke()
        val id = url.removePrefix("https://dl.test/").substringBefore('?')
        val bytes = api.files[id]?.second ?: return Fetch.NotFound
        val start = if (ignoreRange) 0 else from
        var body = bytes.copyOfRange(start.toInt(), bytes.size)
        if (corrupt) body = body.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }
        truncateFirstBodyAt?.let { cut ->
            if (requests.size == 1) body = body.copyOfRange(0, cut)
        }
        return Fetch.Body(start, bytes.size.toLong(), body.toResponseBody())
    }
}

class FakePublisher : DownloadPublisher {
    val published = mutableListOf<Pair<String, ByteArray>>()
    var cached: File? = null

    override suspend fun publish(file: File, name: String, mime: String?, relDir: String, dest: DownloadDest, treeUri: String?, cacheKey: String?): String {
        published += name to file.readBytes()
        file.delete()
        return "content://published/$name"
    }

    override fun cachedOpenFile(cacheKey: String, name: String, size: Long): File? = cached
}
