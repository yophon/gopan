package xyz.yophon.gopan.testutil

import com.apollographql.apollo.ApolloClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionManager
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.network.AuthInterceptor
import xyz.yophon.gopan.core.network.GopanCookieJar
import xyz.yophon.gopan.core.network.InMemorySecureStore
import xyz.yophon.gopan.core.network.TokenRefresher
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import xyz.yophon.gopan.core.network.withoutRedirects

/** 把 DI 根里那一套对象手工装起来,指向 [FakeGopanServer](以及可选的假账号中心)。 */
class TestHarness(
    val gopan: FakeGopanServer,
    idIssuer: String = "http://id.invalid",
    /** 传入上一个 harness 的存储即可模拟"进程重启、加密存储还在" */
    val secureStore: InMemorySecureStore = InMemorySecureStore(),
) : AutoCloseable {
    val config = AppConfig(
        baseUrl = gopan.baseUrl,
        idIssuer = idIssuer,
        idClientId = "gopan-android",
        idRedirectUri = "xyz.yophon.gopan:/oauthredirect",
    )
    val cookieJar = GopanCookieJar(gopan.host, secureStore)
    val okHttp: OkHttpClient = buildOkHttpClient(cookieJar)
    val noRedirectOkHttp: OkHttpClient = okHttp.withoutRedirects()
    val store = SessionStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var sessionManager: SessionManager
        private set

    val apollo: ApolloClient = buildApolloClient(
        config.graphqlUrl,
        okHttp,
        AuthInterceptor(store, dagger.Lazy<TokenRefresher> { sessionManager }),
    )

    /** 登出 / 会话失效时被调用的次数(验证清理钩子确实跑了) */
    val signOutHookCalls = java.util.concurrent.atomic.AtomicInteger()

    init {
        sessionManager = SessionManager(
            apollo,
            store,
            cookieJar,
            secureStore,
            scope,
            config,
            okHttp,
            setOf(SignOutHook { signOutHookCalls.incrementAndGet() }),
        )
    }

    override fun close() {
        apollo.close()
        scope.cancel()
    }
}
