package xyz.yophon.gopan.core.auth

import com.apollographql.apollo.ApolloClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.network.GopanCookieJar
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.SecureStore
import xyz.yophon.gopan.core.network.TokenRefresher
import xyz.yophon.gopan.core.network.await
import xyz.yophon.gopan.core.network.isUnauthenticated
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.di.ApplicationScope
import xyz.yophon.gopan.graphql.ChangePasswordMutation
import xyz.yophon.gopan.graphql.LoginMutation
import xyz.yophon.gopan.graphql.MeQuery
import xyz.yophon.gopan.graphql.RefreshMutation
import xyz.yophon.gopan.graphql.RegisterMutation
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话生命周期:启动续登、密码登录/注册、单飞 refresh、登出、改密。
 * 协议与网页端一致:refresh token 在 httpOnly cookie(CookieJar 持久化);access token 另外存进加密存储,
 * 冷启动时还有一段有效期就直接用,省掉那次 refresh(服务端吊销会话时它当场失效,走下面的 refresh 流程)。
 *
 * refresh 的三种结果:
 * - OK:拿到新 token,SignedIn;
 * - REJECTED:服务端返回 UNAUTHENTICATED(cookie 过期/被吊销/账号中心撤销设备)→ 清会话、SignedOut;
 *   或者刷新途中用户登出 / 重新登录,结果作废;
 * - UNAVAILABLE:网络不通或服务端 5xx/其它错误 → 不动 cookie,登录态保持原样。
 *
 * 登出与刷新的竞态用 [epoch] 处理:登出、显式登录都会推进它;刷新落地时发现 epoch 变了就作废结果,
 * 登出流程会等在途刷新落地后再清 cookie(否则刷新响应里的 Set-Cookie 会把会话"复活")。
 */
@Singleton
class SessionManager @Inject constructor(
    private val apollo: ApolloClient,
    private val store: SessionStore,
    private val cookieJar: GopanCookieJar,
    private val secureStore: SecureStore,
    @ApplicationScope private val appScope: CoroutineScope,
    private val config: AppConfig,
    private val okHttpClient: OkHttpClient,
    private val signOutHooks: Set<@JvmSuppressWildcards SignOutHook>,
) : TokenRefresher {

    private enum class RefreshResult { OK, REJECTED, UNAVAILABLE }

    private class InFlight(val epoch: Long, val result: CompletableDeferred<RefreshResult>)

    private val lock = Any()
    private var inFlight: InFlight? = null
    private var bootstrapStarted = false
    private val epoch = AtomicLong(0)

    /** 上次 refresh 因网络失败而无法判定的时刻(epoch 毫秒);成功后归零 */
    @Volatile
    private var lastUnavailableAt = 0L

    /** 通知服务端吊销用:不带 CookieJar(手工带上要吊销的那枚 cookie,响应里的删除指令也不会误伤新会话) */
    private val revokeClient: OkHttpClient by lazy { okHttpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }

    /** Application.onCreate 调用:后台跑 [bootstrap],只跑一次 */
    fun startBootstrap() {
        synchronized(lock) {
            if (bootstrapStarted) return
            bootstrapStarted = true
        }
        appScope.launch { bootstrap() }
    }

    /**
     * 启动续登:没有 cookie 直接 SignedOut;有 cookie 就 refresh。
     * 有上次缓存的用户时不等网络:存下的 access token 离过期还有 [REUSE_MIN_REMAINING_MS] 以上,就带着它进入 SignedIn,
     * 业务请求立刻发出,这次不 refresh;否则先用缓存的用户进入 SignedIn(token 为空),主界面立刻画出来(目录先显示本地缓存),
     * refresh 接着在这里跑——成功换上 token;被拒清会话回登录页;断网保持现状,首个业务请求联网后再刷新。
     * 没有缓存的用户才停在启动页等 refresh,最多 [BOOTSTRAP_TIMEOUT_MS],超时或断网按未登录处理(cookie 保留)。
     */
    suspend fun bootstrap() {
        if (store.state.value !is AuthState.Loading) return
        val (hasCookie, cached, saved) = withContext(Dispatchers.IO) {
            Triple(cookieJar.hasCookie(REFRESH_COOKIE), loadCachedUser(), secureStore.get(KEY_ACCESS_TOKEN))
        }
        if (!hasCookie) {
            clearLocal()
            return
        }
        if (cached != null) {
            if (store.state.value !is AuthState.Loading) return
            val expiresAt = saved?.let(AccessTokens::expiresAtMillis)
            if (saved != null && expiresAt != null && expiresAt - System.currentTimeMillis() > REUSE_MIN_REMAINING_MS) {
                store.setSignedIn(saved, cached)
                return
            }
            store.setSignedIn(null, cached)
            refreshInternal()
            return
        }
        val result = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) { refreshInternal() } ?: RefreshResult.UNAVAILABLE
        // 后台那次 refresh 可能已经先落地(OK / REJECTED 都已写好登录态)
        if (result == RefreshResult.UNAVAILABLE && store.state.value is AuthState.Loading) store.setSignedOut()
    }

    suspend fun loginWithPassword(username: String, password: String) {
        beginSignIn()
        val data = apollo.mutation(LoginMutation(username = username, password = password)).execute().requireData()
        establish(data.login.accessToken, data.login.user.userFields.toSummary())
    }

    suspend fun register(username: String, password: String) {
        beginSignIn()
        val data = apollo.mutation(RegisterMutation(username = username, password = password)).execute().requireData()
        establish(data.register.accessToken, data.register.user.userFields.toSummary())
    }

    /**
     * 一次显式登录开始(密码 / 注册 / Yophon ID 票据交换):推进 epoch,
     * 让还在收尾的登出流程不再清掉即将写入的新 cookie,也让旧的在途 refresh 作废。
     */
    fun beginSignIn() {
        epoch.incrementAndGet()
    }

    /** 单飞刷新:并发调用共享同一次请求。成功 true;服务端拒绝(已清会话)或网络失败 false。 */
    suspend fun refresh(): Boolean = refreshInternal() == RefreshResult.OK

    override suspend fun accessTokenForRequest(): String? {
        store.accessToken?.let { return it }
        if (store.state.value !is AuthState.SignedIn) return null
        // 刚因网络失败刷新不成:别让每个请求都先白等一次刷新(连不上服务端时每次要等到超时)
        if (System.currentTimeMillis() - lastUnavailableAt < UNAVAILABLE_COOLDOWN_MS) return null
        refreshInternal()
        return store.accessToken
    }

    override suspend fun refreshAfterUnauthenticated(staleToken: String?): RefreshOutcome {
        // 已登出:不再用残留的 cookie 去刷新(否则后台请求会把刚登出的会话刷回来)
        if (store.state.value is AuthState.SignedOut) return RefreshOutcome.SignedOut
        // 别的请求刚刷新过(当前 token 已不是触发 401 的那个),直接复用,不再发请求
        val current = store.accessToken
        if (current != null && current != staleToken) return RefreshOutcome.Refreshed(current)
        return when (refreshInternal()) {
            RefreshResult.OK -> store.accessToken?.let { RefreshOutcome.Refreshed(it) } ?: RefreshOutcome.SignedOut
            RefreshResult.REJECTED -> RefreshOutcome.SignedOut
            RefreshResult.UNAVAILABLE -> RefreshOutcome.Unavailable
        }
    }

    /**
     * 登出。界面立刻回到登录页;收尾在应用作用域里做完,调用方页面销毁也不会中断:
     * 等在途 refresh 落地 → 清 cookie、缓存的用户与各模块本地数据 → 带着原 cookie 通知服务端吊销 refresh family。
     * 网络不通时服务端那枚 token 会在 14 天后自然过期,本机已经清干净。
     */
    suspend fun logout() {
        val myEpoch = epoch.incrementAndGet()
        store.setSignedOut()
        appScope.launch { finishLogout(myEpoch) }.join()
    }

    /**
     * 改密码。服务端会吊销其它设备的会话并给当前会话重新签发一对 token(响应里的 accessToken
     * 与 Set-Cookie),所以这里和网页端一样直接采用新凭证,当前设备不需要重新登录。
     */
    suspend fun changePassword(oldPassword: String, newPassword: String) {
        val data = apollo.mutation(ChangePasswordMutation(oldPassword = oldPassword, newPassword = newPassword))
            .execute().requireData()
        establish(data.changePassword.accessToken, data.changePassword.user.userFields.toSummary())
    }

    /** 重新拉取 me(用量变化后刷新「我的」页) */
    suspend fun reloadUser(): UserSummary {
        val user = apollo.query(MeQuery()).execute().requireData().me.userFields.toSummary()
        store.updateUser(user)
        withContext(Dispatchers.IO) { cacheUser(user) }
        return user
    }

    // ---- 内部 ----

    private suspend fun refreshInternal(): RefreshResult {
        val current = epoch.get()
        val deferred = synchronized(lock) {
            val existing = inFlight
            // 只复用同一 epoch 内发起的那次:登出后重新登录时不能去等旧会话的刷新
            if (existing != null && existing.epoch == current) {
                existing.result
            } else {
                val created = InFlight(current, CompletableDeferred())
                inFlight = created
                appScope.launch {
                    var result = RefreshResult.UNAVAILABLE
                    try {
                        result = doRefresh(created.epoch)
                    } catch (e: Throwable) {
                        if (e !is CancellationException) Timber.w(e, "refresh 异常")
                    } finally {
                        lastUnavailableAt = if (result == RefreshResult.UNAVAILABLE) System.currentTimeMillis() else 0L
                        synchronized(lock) { if (inFlight === created) inFlight = null }
                        created.result.complete(result)
                    }
                }
                created.result
            }
        }
        return deferred.await()
    }

    private suspend fun doRefresh(startEpoch: Long): RefreshResult {
        val response = apollo.mutation(RefreshMutation()).execute()
        // 刷新途中用户登出或重新登录了:这次的结果属于旧会话,作废(登出流程会在之后清掉它写进来的 cookie)
        if (epoch.get() != startEpoch) return RefreshResult.REJECTED
        if (response.exception != null) {
            Timber.d("refresh 传输失败:%s", response.exception?.javaClass?.simpleName)
            return RefreshResult.UNAVAILABLE
        }
        if (response.isUnauthenticated()) {
            clearLocal()
            return RefreshResult.REJECTED
        }
        val data = response.data
        if (data == null) {
            // 其它 GraphQL 错误(INTERNAL 等):服务端若已经删掉了 cookie,会话实际已失效,不要停在"已登录但无凭证"
            val stillHasCookie = withContext(Dispatchers.IO) { cookieJar.hasCookie(REFRESH_COOKIE) }
            if (!stillHasCookie) {
                clearLocal()
                return RefreshResult.REJECTED
            }
            return RefreshResult.UNAVAILABLE
        }
        establish(data.refresh.accessToken, data.refresh.user.userFields.toSummary())
        return RefreshResult.OK
    }

    private suspend fun establish(accessToken: String, user: UserSummary) {
        store.setSignedIn(accessToken, user)
        withContext(Dispatchers.IO) {
            cacheUser(user)
            secureStore.put(KEY_ACCESS_TOKEN, accessToken)
        }
    }

    /** 会话失效(refresh 被拒、启动时没有 cookie):清登录态、cookie 与本地数据 */
    private suspend fun clearLocal() {
        store.setSignedOut()
        withContext(Dispatchers.IO) {
            cookieJar.clear()
            secureStore.put(KEY_LAST_USER, null)
            secureStore.put(KEY_ACCESS_TOKEN, null)
        }
        runSignOutHooks()
    }

    private suspend fun finishLogout(myEpoch: Long) {
        val token = withContext(Dispatchers.IO) { cookieJar.valueOf(REFRESH_COOKIE) }
        val pending = synchronized(lock) { inFlight }
        if (pending != null) withTimeoutOrNull(LOGOUT_REFRESH_WAIT_MS) { pending.result.await() }
        // 期间用户已经重新登录:新会话的 cookie 不能清
        if (epoch.get() == myEpoch) {
            withContext(Dispatchers.IO) {
                cookieJar.clear()
                secureStore.put(KEY_LAST_USER, null)
                secureStore.put(KEY_ACCESS_TOKEN, null)
            }
        }
        runSignOutHooks()
        if (token != null) revokeOnServer(token)
    }

    /** 服务端按任意一枚 token 吊销整个 family(即使它已被轮换过),所以用登出瞬间那枚即可 */
    private suspend fun revokeOnServer(refreshToken: String) {
        val body = LOGOUT_BODY.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(config.graphqlUrl)
            .header("Cookie", "$REFRESH_COOKIE=$refreshToken")
            .post(body)
            .build()
        try {
            withTimeoutOrNull(LOGOUT_REQUEST_TIMEOUT_MS) {
                revokeClient.newCall(request).await().close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("通知服务端登出失败:%s", e.javaClass.simpleName)
        }
    }

    private suspend fun runSignOutHooks() {
        for (hook in signOutHooks) {
            try {
                hook.onSignedOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "登出清理失败")
            }
        }
    }

    private fun cacheUser(user: UserSummary) {
        secureStore.put(KEY_LAST_USER, json.encodeToString(user))
    }

    private fun loadCachedUser(): UserSummary? =
        secureStore.get(KEY_LAST_USER)?.let { raw -> runCatching { json.decodeFromString<UserSummary>(raw) }.getOrNull() }

    companion object {
        const val REFRESH_COOKIE = "gopan_rt"
        const val KEY_LAST_USER = "last_user"
        const val KEY_ACCESS_TOKEN = "access_token"

        /** 冷启动时存下的 access token 至少还剩这么久才直接用(服务端默认签 4 小时) */
        const val REUSE_MIN_REMAINING_MS = 5 * 60_000L

        /** 没有缓存用户时启动页最多等 refresh 这么久,超时按未登录处理 */
        const val BOOTSTRAP_TIMEOUT_MS = 8_000L

        /** refresh 因网络失败后的这段时间里,业务请求不再先等一次刷新,直接不带 token 发出 */
        const val UNAVAILABLE_COOLDOWN_MS = 5_000L

        /** 登出时等在途 refresh 落地的上限 */
        const val LOGOUT_REFRESH_WAIT_MS = 15_000L
        const val LOGOUT_REQUEST_TIMEOUT_MS = 10_000L

        private const val LOGOUT_BODY = """{"operationName":"Logout","query":"mutation Logout { logout }","variables":{}}"""
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
