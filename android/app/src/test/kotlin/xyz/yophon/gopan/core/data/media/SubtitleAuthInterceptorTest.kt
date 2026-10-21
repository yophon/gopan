package xyz.yophon.gopan.core.data.media

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher

/** 字幕鉴权:只有本站 /sub/ 带 Bearer,401 刷新一次重放;预签名地址不带凭证 */
class SubtitleAuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var token: String? = "t1"
    private var outcome: RefreshOutcome = RefreshOutcome.Refreshed("t2")
    private val refreshedWith = mutableListOf<String?>()

    private val refresher = object : TokenRefresher {
        override suspend fun refreshAfterUnauthenticated(staleToken: String?): RefreshOutcome {
            refreshedWith += staleToken
            (outcome as? RefreshOutcome.Refreshed)?.let { token = it.accessToken }
            return outcome
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val interceptor = SubtitleAuthInterceptor(server.url("/").toString(), { token }, { refresher })
        client = OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String): Int = client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.code }

    @Test
    fun `subtitle requests carry the bearer token`() {
        server.enqueue(MockResponse().setBody("WEBVTT"))
        assertThat(get("/sub/n1")).isEqualTo(200)
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer t1")
        assertThat(recorded.requestUrl?.queryParameter("token")).isNull()
    }

    @Test
    fun `presigned media urls never get credentials`() {
        server.enqueue(MockResponse().setBody("x"))
        server.enqueue(MockResponse().setBody("x"))
        get("/gopan/blobs/ab?X-Amz-Signature=1")
        get("/subtitles/n1")
        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `an expired token is refreshed once and the request replayed`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("WEBVTT"))
        assertThat(get("/sub/n1")).isEqualTo(200)
        assertThat(refreshedWith).containsExactly("t1")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer t1")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer t2")
    }

    @Test
    fun `a refused refresh hands back the 401`() {
        outcome = RefreshOutcome.SignedOut
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(get("/sub/n1")).isEqualTo(401)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `only the site's own sub path matches`() {
        val interceptor = SubtitleAuthInterceptor("https://drive.example.com/", { token }, { refresher })
        assertThat(interceptor.matches("https://drive.example.com/sub/n1".toHttpUrl())).isTrue()
        assertThat(interceptor.matches("https://drive.example.com/sub/n1?token=x".toHttpUrl())).isTrue()
        assertThat(interceptor.matches("http://drive.example.com/sub/n1".toHttpUrl())).isFalse()
        assertThat(interceptor.matches("https://s3.example.com/sub/n1".toHttpUrl())).isFalse()
        assertThat(interceptor.matches("https://drive.example.com:8443/sub/n1".toHttpUrl())).isFalse()
        assertThat(interceptor.matches("https://drive.example.com/query".toHttpUrl())).isFalse()
    }

    @Test
    fun `subtitle urls point at the site`() {
        assertThat(subtitleUrl("https://drive.example.com/", "0192-ab")).isEqualTo("https://drive.example.com/sub/0192-ab")
        assertThat(subtitleUrl("https://drive.example.com", "x")).isEqualTo("https://drive.example.com/sub/x")
    }
}
