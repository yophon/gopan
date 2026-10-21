package xyz.yophon.gopan.core.data.media

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class MediaUrlsTest {

    private var clock = 0L
    private val fetched = mutableListOf<String>()
    private var serial = 0
    private val urls = MediaUrls(
        fetch = { id ->
            fetched += id
            if (id == "gone") null else "https://s3/$id?sig=${++serial}"
        },
        now = { clock },
    )

    @Test
    fun `placeholders carry the node id`() {
        assertThat(MediaUrls.placeholder("0192-ab")).isEqualTo("gopan-node://0192-ab")
        assertThat(MediaUrls.nodeIdOf("gopan-node://0192-ab")).isEqualTo("0192-ab")
        assertThat(MediaUrls.nodeIdOf(MediaUrls.placeholder("x"))).isEqualTo("x")
        assertThat(MediaUrls.nodeIdOf("https://s3/a")).isNull()
        assertThat(MediaUrls.nodeIdOf("gopan-node://")).isNull()
    }

    @Test
    fun `a url is fetched once and reused within its lifetime`() = runBlocking<Unit> {
        assertThat(urls.resolve("a")).isEqualTo("https://s3/a?sig=1")
        clock += MediaUrls.TTL_MS - 1
        assertThat(urls.resolve("a")).isEqualTo("https://s3/a?sig=1")
        assertThat(fetched).containsExactly("a")
        // 用了 10 分钟:换新的,免得建连时已经过期
        clock += 1
        assertThat(urls.resolve("a")).isEqualTo("https://s3/a?sig=2")
        assertThat(fetched).containsExactly("a", "a")
    }

    @Test
    fun `an expired url is dropped and fetched again`() = runBlocking<Unit> {
        urls.resolve("a")
        urls.invalidate("a")
        assertThat(urls.resolve("a")).isEqualTo("https://s3/a?sig=2")
    }

    @Test
    fun `a seeded url saves the first lookup`() = runBlocking<Unit> {
        urls.seed("a", "https://s3/a?seeded")
        assertThat(urls.resolve("a")).isEqualTo("https://s3/a?seeded")
        assertThat(fetched).isEmpty()
    }

    @Test
    fun `clearing forgets everything`() = runBlocking<Unit> {
        urls.resolve("a")
        urls.clear()
        urls.resolve("a")
        assertThat(fetched).containsExactly("a", "a")
    }

    @Test
    fun `no playable url is a load error`() {
        assertThrows(IOException::class.java) { runBlocking { urls.resolve("gone") } }
    }
}
