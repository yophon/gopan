package xyz.yophon.gopan.core.data.media

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher

/**
 * 播放器的 OkHttp 拦截器:只给本站的 `/sub/…`(外挂字幕)加 `Authorization: Bearer`,401 时单飞刷新一次凭证重放;
 * 其它请求(对象存储的预签名地址)原样放行,不带任何凭证。token 不进 URL(不会出现在日志与崩溃报告里)。
 * 在 ExoPlayer 的加载线程上执行,刷新凭证直接阻塞等结果。
 */
class SubtitleAuthInterceptor(
    baseUrl: String,
    private val accessToken: () -> String?,
    private val refresher: () -> TokenRefresher,
) : Interceptor {

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** 本站(同 scheme / host / 端口)的 /sub/ 路径 */
    fun matches(url: HttpUrl): Boolean =
        url.scheme == base.scheme && url.host == base.host && url.port == base.port && url.encodedPath.startsWith("/sub/")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!matches(request.url)) return chain.proceed(request)
        val token = accessToken()
        val first = chain.proceed(request.withBearer(token))
        if (first.code != 401) return first
        val outcome = runBlocking { refresher().refreshAfterUnauthenticated(token) }
        if (outcome !is RefreshOutcome.Refreshed) return first
        first.close()
        return chain.proceed(request.withBearer(outcome.accessToken))
    }

    private fun Request.withBearer(token: String?): Request =
        if (token.isNullOrEmpty()) this else newBuilder().header("Authorization", "Bearer $token").build()
}
