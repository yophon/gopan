package xyz.yophon.gopan.core.data.markdown

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher
import java.util.Base64

/** /md/ 渲染页:地址拼法、WebView 里点链接的去向、token 现取 */
class MarkdownUrlsTest {

    private val base = "https://pan.example.com"

    // ---- 地址 ----

    @Test
    fun `page url carries the token and the theme`() {
        assertThat(MarkdownLinks.pageUrl(base, "n1", "tok", dark = true)).isEqualTo("https://pan.example.com/md/n1?token=tok&theme=dark")
        assertThat(MarkdownLinks.pageUrl("$base/", "n1", "tok", dark = false)).isEqualTo("https://pan.example.com/md/n1?token=tok&theme=light")
        // 不指定深浅:不带 theme
        assertThat(MarkdownLinks.pageUrl(base, "n1", "tok", dark = null)).isEqualTo("https://pan.example.com/md/n1?token=tok")
        assertThat(MarkdownLinks.pageUrl("http://10.0.2.2:8090", "n1", "tok", dark = true)).isEqualTo("http://10.0.2.2:8090/md/n1?token=tok&theme=dark")
    }

    @Test
    fun `page url encodes the node id and the token`() {
        val url = MarkdownLinks.pageUrl(base, "a b/c", "x+y=z&w", dark = true).toHttpUrl()
        assertThat(url.encodedPath).isEqualTo("/md/a%20b%2Fc")
        assertThat(url.queryParameter("token")).isEqualTo("x+y=z&w")
        assertThat(url.queryParameter("theme")).isEqualTo("dark")
        assertThat(url.querySize).isEqualTo(2)
        // JWT 本身的字符原样出现
        assertThat(MarkdownLinks.pageUrl(base, "n", "eyJh.eyJz-_.sig", dark = null)).endsWith("?token=eyJh.eyJz-_.sig")
    }

    @Test
    fun `withToken replaces only the token`() {
        val old = "https://pan.example.com/md/n1/f?p=a%2F1.md&token=old&theme=dark"
        val fresh = MarkdownLinks.withToken(old, "new").toHttpUrl()
        assertThat(fresh.queryParameter("token")).isEqualTo("new")
        assertThat(fresh.queryParameter("p")).isEqualTo("a/1.md")
        assertThat(fresh.queryParameter("theme")).isEqualTo("dark")
        assertThat(fresh.queryParameterValues("token")).containsExactly("new")
        assertThat(MarkdownLinks.withToken("not a url", "new")).isEqualTo("not a url")
    }

    // ---- 点链接 ----

    @Test
    fun `same-host md links stay inside`() {
        assertThat(MarkdownLinks.route("https://pan.example.com/md/n2?token=t", base)).isEqualTo(MarkdownLinkRoute.INSIDE)
        assertThat(MarkdownLinks.route("https://pan.example.com/md/n1/f?p=..%2Fb.md&token=t", base)).isEqualTo(MarkdownLinkRoute.INSIDE)
        assertThat(MarkdownLinks.route("https://PAN.example.com:443/md/n2", "$base/")).isEqualTo(MarkdownLinkRoute.INSIDE)
    }

    @Test
    fun `everything else on the web goes to the browser`() {
        assertThat(MarkdownLinks.route("https://other.com/md/n2", base)).isEqualTo(MarkdownLinkRoute.EXTERNAL)
        // 本站的其它页面(分享页、直链)也交给浏览器
        assertThat(MarkdownLinks.route("https://pan.example.com/s/abc", base)).isEqualTo(MarkdownLinkRoute.EXTERNAL)
        assertThat(MarkdownLinks.route("https://pan.example.com/mdx/n", base)).isEqualTo(MarkdownLinkRoute.EXTERNAL)
        // 端口、协议不同不算本站
        assertThat(MarkdownLinks.route("https://pan.example.com:8443/md/n2", base)).isEqualTo(MarkdownLinkRoute.EXTERNAL)
        assertThat(MarkdownLinks.route("http://pan.example.com/md/n2", base)).isEqualTo(MarkdownLinkRoute.EXTERNAL)
        // 图片的签名地址(对象存储)
        assertThat(MarkdownLinks.route("https://s3.example.com/bucket/x.png?X-Amz-Signature=1", base)).isEqualTo(MarkdownLinkRoute.EXTERNAL)
        assertThat(MarkdownLinks.route("mailto:someone@example.com", base)).isEqualTo(MarkdownLinkRoute.EXTERNAL)
        assertThat(MarkdownLinks.route("tel:10086", base)).isEqualTo(MarkdownLinkRoute.EXTERNAL)
    }

    @Test
    fun `local and script schemes are blocked`() {
        listOf(
            "javascript:alert(1)",
            "file:///sdcard/a.txt",
            "content://xyz.yophon.gopan.files/x",
            "intent://scan/#Intent;scheme=zxing;end",
            "data:text/html,<b>x</b>",
            "about:blank",
            "",
        ).forEach { assertThat(MarkdownLinks.route(it, base)).isEqualTo(MarkdownLinkRoute.BLOCK) }
    }

    // ---- token ----

    private fun jwt(expSeconds: Long?): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val claims = if (expSeconds == null) """{"sub":"u1"}""" else """{"sub":"u1","exp":$expSeconds}"""
        return "$header.${enc.encodeToString(claims.toByteArray())}.sig"
    }

    @Test
    fun `token expiry is read from the jwt`() {
        assertThat(MarkdownLinks.expiresAtMillis(jwt(1_700_000_000))).isEqualTo(1_700_000_000_000)
        assertThat(MarkdownLinks.expiresAtMillis(jwt(null))).isNull()
        assertThat(MarkdownLinks.expiresAtMillis("opaque-token")).isNull()
        assertThat(MarkdownLinks.expiresAtMillis("a.!!!.c")).isNull()
        val now = 1_700_000_000_000
        assertThat(MarkdownLinks.expiresSoon(jwt(now / 1000 + 600), now)).isFalse()
        assertThat(MarkdownLinks.expiresSoon(jwt(now / 1000 + 60), now)).isTrue()
        assertThat(MarkdownLinks.expiresSoon(jwt(now / 1000 - 10), now)).isTrue()
        // 不知道什么时候过期:照用
        assertThat(MarkdownLinks.expiresSoon("opaque-token", now)).isFalse()
    }

    private class FakeRefresher(var outcome: RefreshOutcome) : TokenRefresher {
        val calls = mutableListOf<String?>()

        override suspend fun refreshAfterUnauthenticated(staleToken: String?): RefreshOutcome {
            calls += staleToken
            return outcome
        }
    }

    private fun urls(token: String?, refresher: FakeRefresher, now: Long): MarkdownUrls {
        val session = SessionStore().apply { setSignedIn(token, UserSummary("u1", "me", 0, 0, false)) }
        return MarkdownUrls(AppConfig(base, "https://id.example.com", "c", "r"), session) { refresher }.also { it.now = { now } }
    }

    @Test
    fun `a token with time left is used as is`() = runBlocking {
        val now = 1_700_000_000_000
        val token = jwt(now / 1000 + 900)
        val refresher = FakeRefresher(RefreshOutcome.Refreshed("other"))
        val u = urls(token, refresher, now)
        assertThat(u.page("n1", dark = true)).isEqualTo("$base/md/n1?token=$token&theme=dark")
        assertThat(refresher.calls).isEmpty()
    }

    @Test
    fun `an expiring or missing token is refreshed first`() = runBlocking {
        val now = 1_700_000_000_000
        val stale = jwt(now / 1000 + 30)
        val refresher = FakeRefresher(RefreshOutcome.Refreshed("fresh"))
        assertThat(urls(stale, refresher, now).page("n1", dark = false)).isEqualTo("$base/md/n1?token=fresh&theme=light")
        assertThat(refresher.calls).containsExactly(stale)

        val none = FakeRefresher(RefreshOutcome.Refreshed("fresh2"))
        assertThat(urls(null, none, now).retoken("$base/md/n1/f?p=b.md&token=old")).isEqualTo("$base/md/n1/f?p=b.md&token=fresh2")
    }

    @Test
    fun `refresh failures keep the old token or give up`() = runBlocking {
        val now = 1_700_000_000_000
        val stale = jwt(now / 1000 + 30)
        // 断网:先用手上的
        assertThat(urls(stale, FakeRefresher(RefreshOutcome.Unavailable), now).token()).isEqualTo(stale)
        // 已登出 / 手上也没有:失败
        assertThrows(GopanException::class.java) { runBlocking { urls(stale, FakeRefresher(RefreshOutcome.SignedOut), now).token() } }
        assertThrows(GopanException::class.java) { runBlocking { urls(null, FakeRefresher(RefreshOutcome.Unavailable), now).token() } }
        Unit
    }
}
