package xyz.yophon.gopan.core.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.network.okHttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TIMEOUT_SECONDS = 30L

/**
 * 全 App 共用的 OkHttpClient:带 CookieJar,30 秒超时。Apollo、Coil、下载都从它派生。
 * [userAgent] 非空时每个请求都带上(登录设备、分享访问记录据此认出「gopan 安卓 App」)。
 */
fun buildOkHttpClient(cookieJar: CookieJar, userAgent: String? = null): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .apply {
        if (userAgent != null) {
            addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", userAgent).build() else request)
            }
        }
    }
    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

/**
 * App 的 User-Agent:`gopan-android/3.0.0 (Android 16; HUAWEI AAK-AN00)`。
 * 请求头只能是可见 ASCII,机型名里的其它字符(中文型号等)去掉。
 */
fun appUserAgent(versionName: String, release: String, manufacturer: String, model: String): String {
    val device = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
    val raw = "gopan-android/$versionName (Android $release; $device)"
    return raw.filter { it in ' '..'~' }.replace(Regex(" {2,}"), " ")
}

/** 不自动跟随 3xx 的派生客户端(共享连接池与 CookieJar),票据交换要亲自看到那个 303。 */
fun OkHttpClient.withoutRedirects(): OkHttpClient = newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** GraphQL 客户端:POST {base}/query,与 OkHttpClient 共享 cookie;拦截器顺序即传入顺序。 */
fun buildApolloClient(
    graphqlUrl: String,
    okHttpClient: OkHttpClient,
    vararg interceptors: ApolloInterceptor,
): ApolloClient = ApolloClient.Builder()
    .serverUrl(graphqlUrl)
    .okHttpClient(okHttpClient)
    .apply { interceptors.forEach { addInterceptor(it) } }
    .build()

/** OkHttp Call 的挂起版本;取消协程即取消请求。 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { cancel() }
}

fun OkHttpClient.newCall(block: Request.Builder.() -> Unit): Call = newCall(Request.Builder().apply(block).build())
