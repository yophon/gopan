package xyz.yophon.gopan.core.data.preview

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class TextFetcherTest {

    private val server = MockWebServer().apply { start() }
    private val fetcher = TextFetcher(OkHttpClient())

    @After
    fun tearDown() = server.shutdown()

    private fun partial(all: ByteArray, from: Int, max: Int): MockResponse {
        val end = minOf(all.size, from + max)
        return MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes $from-${end - 1}/${all.size}")
            .setBody(Buffer().write(all.copyOfRange(from, end)))
    }

    @Test
    fun `an offline copy is read from the file in the same segments`() = runBlocking {
        val all = "汉字ab".toByteArray()
        val file = File.createTempFile("text", ".txt").apply { writeBytes(all); deleteOnExit() }

        val first = fetcher.fetch(file.toURI().toString(), maxBytes = 4)
        assertThat(first.text).isEqualTo("汉")
        assertThat(first.truncated).isTrue()
        assertThat(first.nextOffset).isEqualTo(3)
        assertThat(first.totalBytes).isEqualTo(8)

        val rest = fetcher.fetch(file.toURI().toString(), offset = first.nextOffset, charset = first.charset, maxBytes = 8)
        assertThat(rest.text).isEqualTo("字ab")
        assertThat(rest.truncated).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `reads in byte ranges and never splits a character between segments`() = runBlocking {
        val all = "汉字ab".toByteArray() // 3 + 3 + 2 字节
        server.enqueue(partial(all, 0, 4))
        val first = fetcher.fetch(server.url("/t").toString(), maxBytes = 4)
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=0-3")
        assertThat(first.text).isEqualTo("汉")
        assertThat(first.truncated).isTrue()
        assertThat(first.nextOffset).isEqualTo(3)
        assertThat(first.totalBytes).isEqualTo(8)

        server.enqueue(partial(all, 3, 4))
        val second = fetcher.fetch(server.url("/t").toString(), offset = first.nextOffset, charset = first.charset, maxBytes = 4)
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=3-6")
        assertThat(second.text).isEqualTo("字a")
        assertThat(second.nextOffset).isEqualTo(7)
        assertThat(second.truncated).isTrue()
    }

    @Test
    fun `the bom is skipped and counted`() = runBlocking {
        val all = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        server.enqueue(partial(all, 0, 100))
        val t = fetcher.fetch(server.url("/t").toString())
        assertThat(t.text).isEqualTo("hi")
        assertThat(t.nextOffset).isEqualTo(5)
        assertThat(t.truncated).isFalse()
    }

    @Test
    fun `a server that ignores range still yields the right segment`() = runBlocking {
        val all = "0123456789".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(all)))
        val t = fetcher.fetch(server.url("/t").toString(), offset = 4, charset = "UTF-8", maxBytes = 3)
        assertThat(t.text).isEqualTo("456")
        assertThat(t.nextOffset).isEqualTo(7)
        assertThat(t.totalBytes).isEqualTo(10)
        assertThat(t.truncated).isTrue()
    }

    @Test
    fun `old gbk files are detected and later segments keep the charset`() = runBlocking {
        val gb = Charset.forName("GB18030")
        val all = "中文老文件".toByteArray(gb) // 每字 2 字节
        server.enqueue(partial(all, 0, 5))
        val first = fetcher.fetch(server.url("/t").toString(), maxBytes = 5)
        assertThat(first.charset).isEqualTo("GB18030")
        assertThat(first.text).isEqualTo("中文")
        assertThat(first.nextOffset).isEqualTo(4)

        server.enqueue(partial(all, 4, 100))
        val rest = fetcher.fetch(server.url("/t").toString(), offset = 4, charset = first.charset)
        assertThat(rest.text).isEqualTo("老文件")
        assertThat(rest.truncated).isFalse()
    }

    @Test
    fun `an http error becomes a readable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val e = runCatching { fetcher.fetch(server.url("/t").toString()) }.exceptionOrNull()
        assertThat(e?.message).contains("403")
    }
}
