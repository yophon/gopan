package xyz.yophon.gopan.core.app

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import java.security.MessageDigest
import java.time.Instant

class UpdateRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val config = AppConfig(baseUrl = server.url("/").toString(), idIssuer = "https://id.example", idClientId = "c", idRedirectUri = "x:/cb")
    private val repo by lazy { DefaultUpdateRepository(tmp.newFolder("update"), OkHttpClient(), config) }

    @After
    fun tearDown() = server.shutdown()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun latest(code: Long, apk: String = "gopan-3.0.0.apk", sha: String = "", size: Long = 0) =
        """{"versionCode":$code,"versionName":"3.0.0","apk":"$apk","sha256":"$sha","size":$size,"notes":"正式版","url":"/api/app/android/$apk","extra":1}"""

    @Test
    fun `changelog opens the site release page`() {
        assertThat(repo.changelogUrl).isEqualTo(server.url("/app").toString() + "#changelog")
    }

    @Test
    fun `a newer release on the site is offered`() = runBlocking {
        server.enqueue(MockResponse().setBody(latest(30000)))
        val r = repo.check(currentVersionCode = 30003 - 10)
        assertThat(server.takeRequest().path).isEqualTo("/api/app/android/latest")
        assertThat(r).isInstanceOf(UpdateCheck.Available::class.java)
        assertThat((r as UpdateCheck.Available).release.notes).isEqualTo("正式版")
    }

    @Test
    fun `the same or an older release means up to date`() = runBlocking {
        server.enqueue(MockResponse().setBody(latest(30000)))
        assertThat(repo.check(30000)).isInstanceOf(UpdateCheck.UpToDate::class.java)
    }

    @Test
    fun `a site without releases is reported as not configured`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_configured"}"""))
        assertThat(repo.check(1)).isEqualTo(UpdateCheck.NotConfigured)
    }

    @Test
    fun `an html answer (spa fallback) is a readable error, not a crash`() {
        server.enqueue(MockResponse().setBody("<!doctype html><html></html>"))
        val e = runCatching { runBlocking { repo.check(1) } }.exceptionOrNull()
        assertThat(e).isInstanceOf(GopanException::class.java)
    }

    @Test
    fun `download verifies the checksum and reuses a verified package`() = runBlocking {
        val apk = ByteArray(100_000) { (it % 7).toByte() }
        val release = UpdateRepository_parse(latest(30000, sha = sha(apk), size = apk.size.toLong()))
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        var last = 0L
        val file = repo.download(release) { done, _ -> last = done }
        assertThat(file.readBytes()).isEqualTo(apk)
        assertThat(last).isEqualTo(apk.size.toLong())
        assertThat(server.takeRequest().path).isEqualTo("/api/app/android/gopan-3.0.0.apk")

        // 第二次不再下载
        val again = repo.download(release)
        assertThat(again).isEqualTo(file)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a corrupted download is rejected`() {
        val release = UpdateRepository_parse(latest(30000, sha = "0".repeat(64)))
        server.enqueue(MockResponse().setBody("not the package"))
        val e = runCatching { runBlocking { repo.download(release) } }.exceptionOrNull() as GopanException
        assertThat(e.code).isEqualTo(GopanErrors.HASH_MISMATCH)
    }

    @Test
    fun `a release without a checksum is never downloaded`() {
        for (sha in listOf("", "ab", "z".repeat(64))) {
            val release = UpdateRepository_parse(latest(30000, sha = sha))
            val e = runCatching { runBlocking { repo.download(release) } }.exceptionOrNull()
            assertThat(e).isInstanceOf(GopanException::class.java)
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `local file names keep only safe characters`() {
        assertThat(DefaultUpdateRepository.safeName(UpdateRepository_parse(latest(1, apk = "../evil name.apk")))).isEqualTo("..evilname.apk")
        assertThat(DefaultUpdateRepository.safeName(UpdateRepository_parse(latest(7, apk = "x")))).isEqualTo("x.apk")
    }

    @Suppress("FunctionName")
    private fun UpdateRepository_parse(body: String) = DefaultUpdateRepository.parse(body)
}

class CrashLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `crashes are written newest first and only the last ones are kept`() {
        val log = CrashLog(tmp.newFolder("crash"))
        repeat(CrashLog.KEEP + 3) { i ->
            log.record("main", IllegalStateException("boom $i"), now = Instant.ofEpochSecond(1_000_000L + i))
        }
        val reports = log.reports()
        assertThat(reports).hasSize(CrashLog.KEEP)
        assertThat(reports.first().headline).contains("boom ${CrashLog.KEEP + 2}")
        assertThat(reports.first().file.readText()).contains("线程 main")
    }

    @Test
    fun `presigned urls and bearer tokens never reach the file`() {
        val text = CrashLog.redact("GET https://s3.example/gopan/a.png?X-Amz-Signature=abc&X-Amz-Credential=k failed; Authorization: Bearer eyJhbGc.x.y")
        assertThat(text).doesNotContain("X-Amz-Signature")
        assertThat(text).doesNotContain("eyJhbGc")
        assertThat(text).contains("https://s3.example/gopan/a.png?…")
    }

    @Test
    fun `delete and clear`() {
        val log = CrashLog(tmp.newFolder("crash"))
        log.record("a", RuntimeException("1"), now = Instant.ofEpochSecond(1))
        log.record("b", RuntimeException("2"), now = Instant.ofEpochSecond(2))
        log.delete(log.reports().first())
        assertThat(log.reports()).hasSize(1)
        log.clear()
        assertThat(log.reports()).isEmpty()
    }
}
