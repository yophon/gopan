package xyz.yophon.gopan.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class GopanCookieJarTest {
    private val query = "https://drive.example.com/query".toHttpUrl()
    private val otherPath = "https://drive.example.com/api/auth/id/mobile".toHttpUrl()
    private val otherHost = "https://evil.example.com/query".toHttpUrl()

    private fun cookie(url: okhttp3.HttpUrl, header: String) = listOf(Cookie.parse(url, header)!!)

    @Test
    fun `keeps only cookies for the configured host and matches path`() {
        val store = InMemorySecureStore()
        val jar = GopanCookieJar("drive.example.com", store)

        jar.saveFromResponse(otherPath, cookie(otherPath, "gopan_rt=abc; Path=/query; Max-Age=1209600; HttpOnly; Secure"))
        jar.saveFromResponse(otherHost, cookie(otherHost, "x=1; Path=/"))

        assertThat(jar.loadForRequest(query).map { it.name to it.value }).containsExactly("gopan_rt" to "abc")
        assertThat(jar.loadForRequest(otherPath)).isEmpty()
        assertThat(jar.loadForRequest(otherHost)).isEmpty()
        assertThat(jar.hasCookie("gopan_rt")).isTrue()
        assertThat(jar.hasCookie("x")).isFalse()
    }

    @Test
    fun `persists to the store and reloads with all attributes`() {
        val store = InMemorySecureStore()
        GopanCookieJar("drive.example.com", store)
            .saveFromResponse(query, cookie(query, "gopan_rt=abc; Path=/query; Max-Age=1209600; HttpOnly; Secure"))
        assertThat(store.get(GopanCookieJar.STORE_KEY)).isNotNull()

        val reloaded = GopanCookieJar("drive.example.com", store)
        val c = reloaded.loadForRequest(query).single()
        assertThat(c.value).isEqualTo("abc")
        assertThat(c.path).isEqualTo("/query")
        assertThat(c.secure).isTrue()
        assertThat(c.httpOnly).isTrue()
        assertThat(c.hostOnly).isTrue()
        // Secure cookie 不会发给 http 地址
        assertThat(reloaded.loadForRequest("http://drive.example.com/query".toHttpUrl())).isEmpty()

        reloaded.clear()
        assertThat(store.get(GopanCookieJar.STORE_KEY)).isNull()
        assertThat(GopanCookieJar("drive.example.com", store).hasCookie("gopan_rt")).isFalse()
    }

    @Test
    fun `server-side deletion and expiry drop the cookie`() {
        val store = InMemorySecureStore()
        val jar = GopanCookieJar("drive.example.com", store)
        jar.saveFromResponse(query, cookie(query, "gopan_rt=abc; Path=/query; Max-Age=1209600; HttpOnly"))
        assertThat(jar.hasCookie("gopan_rt")).isTrue()

        // logout:Max-Age=0
        jar.saveFromResponse(query, cookie(query, "gopan_rt=; Path=/query; Max-Age=0; HttpOnly"))
        assertThat(jar.hasCookie("gopan_rt")).isFalse()
        assertThat(store.get(GopanCookieJar.STORE_KEY)).isNull()

        // 已过期的 cookie 写进来直接忽略
        jar.saveFromResponse(query, cookie(query, "gopan_rt=old; Path=/query; Max-Age=1; HttpOnly"))
        Thread.sleep(1100)
        assertThat(jar.loadForRequest(query)).isEmpty()
        assertThat(jar.hasCookie("gopan_rt")).isFalse()
    }
}
