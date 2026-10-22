package xyz.yophon.gopan.core.data.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Markdown 渲染页(doc/23 五.2):GET /md/{nodeId}?token=<access>&theme=light|dark 返回一整页 HTML
 * (服务端已放进 CSP 沙箱,页里的相对图片也已改写成 /md/{id}/f?p=…&token=…)。App 用 WebView 显示,
 * 关掉 JavaScript;token 只能放在地址里(页里的图片要用),所以每次现取一个还没快过期的。
 */

/** WebView 里点了一个链接怎么办 */
enum class MarkdownLinkRoute {
    /** 本站的 /md/ 地址(那篇里链到的另一篇 .md、相对路径的文件):留在 WebView 里,换上新 token 再打开 */
    INSIDE,

    /** 别的网址、本站的其它页面、mailto / tel:交给系统(浏览器、邮件…) */
    EXTERNAL,

    /** file: / content: / javascript: / intent: 之类:什么都不做 */
    BLOCK,
}

object MarkdownLinks {
    private val EXTERNAL_SCHEMES = setOf("mailto", "tel")

    /** [url] 是 WebView 要去的地址;[baseUrl] 是 App 连的服务端 */
    fun route(url: String, baseUrl: String): MarkdownLinkRoute {
        val target = url.trim().toHttpUrlOrNull()
        if (target == null) {
            val scheme = url.trim().substringBefore(':', "").lowercase()
            return if (scheme in EXTERNAL_SCHEMES) MarkdownLinkRoute.EXTERNAL else MarkdownLinkRoute.BLOCK
        }
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return MarkdownLinkRoute.EXTERNAL
        return if (sameOrigin(target, base) && target.encodedPath.startsWith("/md/")) MarkdownLinkRoute.INSIDE else MarkdownLinkRoute.EXTERNAL
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    /** 渲染页地址;[dark] 为 null 时不带 theme(服务端按浏览器的深浅) */
    fun pageUrl(baseUrl: String, nodeId: String, token: String, dark: Boolean?): String =
        baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .encodedPath("/")
            .addPathSegment("md")
            .addPathSegment(nodeId)
            .addQueryParameter("token", token)
            .apply { if (dark != null) addQueryParameter("theme", if (dark) "dark" else "light") }
            .build()
            .toString()

    /** 站内 /md/ 地址里的 token 换成 [token](页面开着久了,里面链接带的 token 已经过期) */
    fun withToken(url: String, token: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        return u.newBuilder().setQueryParameter("token", token).build().toString()
    }

    /** JWT 的 exp(毫秒);不是 JWT、没有 exp 时为 null */
    fun expiresAtMillis(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        val exp = runCatching { Json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return null
        return exp * 1000
    }

    /** 还剩不到 [marginMs] 就算快过期(页里的图片要在这之内加载完) */
    fun expiresSoon(token: String, nowMillis: Long, marginMs: Long = TOKEN_MARGIN_MS): Boolean {
        val exp = expiresAtMillis(token) ?: return false
        return exp - nowMillis < marginMs
    }

    const val TOKEN_MARGIN_MS = 2 * 60 * 1000L
}

/**
 * 拼渲染页地址。token 每次现取:内存里的还有 2 分钟以上就用它,否则(或还没有)先单飞刷新一次;
 * 刷新时网络不通就先用手上的(服务端会回一页「登录已过期」,重试即可)。
 */
@Singleton
class MarkdownUrls @Inject constructor(
    private val config: AppConfig,
    private val session: SessionStore,
    private val refresher: dagger.Lazy<TokenRefresher>,
) {
    /** 时钟:测试里换掉 */
    internal var now: () -> Long = System::currentTimeMillis

    val baseUrl: String get() = config.baseUrl

    suspend fun page(nodeId: String, dark: Boolean?): String = MarkdownLinks.pageUrl(config.baseUrl, nodeId, token(), dark)

    /** WebView 里点到的站内 /md/ 地址:换上新 token */
    suspend fun retoken(url: String): String = MarkdownLinks.withToken(url, token())

    suspend fun token(): String {
        val current = session.accessToken
        if (current != null && !MarkdownLinks.expiresSoon(current, now())) return current
        return when (val outcome = refresher.get().refreshAfterUnauthenticated(current)) {
            is RefreshOutcome.Refreshed -> outcome.accessToken
            RefreshOutcome.SignedOut -> throw GopanException(GopanErrors.UNAUTHENTICATED, GopanErrors.messageFor(GopanErrors.UNAUTHENTICATED, null))
            RefreshOutcome.Unavailable -> current ?: throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null))
        }
    }
}
