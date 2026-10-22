package xyz.yophon.gopan.core.network

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloNetworkException
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import xyz.yophon.gopan.core.auth.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/** 一次 UNAUTHENTICATED 之后的处理结果 */
sealed interface RefreshOutcome {
    /** 已拿到可用的 access token(可能是别人刚刷出来的),用它重放 */
    data class Refreshed(val accessToken: String) : RefreshOutcome

    /** 服务端明确拒绝,会话已清、已进登录页 */
    data object SignedOut : RefreshOutcome

    /** 网络不通之类的临时失败,会话保留,本次请求按网络错误处理 */
    data object Unavailable : RefreshOutcome
}

/** 由 SessionManager 实现;拦截器通过 dagger.Lazy 引用以打破 ApolloClient ↔ SessionManager 的依赖环 */
interface TokenRefresher {
    suspend fun refreshAfterUnauthenticated(staleToken: String?): RefreshOutcome

    /**
     * 发业务请求前取 token:内存里有就用;已登录但还没有(启动时先用缓存用户进来、断网启动)就先等那次单飞 refresh,
     * 省掉"不带 token 发出 → UNAUTHENTICATED → 刷新 → 重放"多出来的一个来回。拿不到返回 null,请求照常不带 token 发出。
     */
    suspend fun accessTokenForRequest(): String? = null
}

/**
 * 业务请求加 Bearer;拿到 UNAUTHENTICATED 则单飞 refresh,成功后换新 token 重放一次。
 * login / register / refresh / logout 四个认证 operation 不加 Bearer、不触发刷新(它们靠 cookie)。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val store: SessionStore,
    private val refresher: dagger.Lazy<TokenRefresher>,
) : ApolloInterceptor {

    override fun <D : Operation.Data> intercept(
        request: ApolloRequest<D>,
        chain: ApolloInterceptorChain,
    ): Flow<ApolloResponse<D>> = flow {
        if (request.operation.name() in AUTH_OPERATIONS) {
            emitAll(chain.proceed(request))
            return@flow
        }
        val token = store.accessToken ?: refresher.get().accessTokenForRequest()
        val first = chain.proceed(request.withBearer(token)).single()
        if (!first.isUnauthenticated()) {
            emit(first)
            return@flow
        }
        when (val outcome = refresher.get().refreshAfterUnauthenticated(token)) {
            is RefreshOutcome.Refreshed -> emitAll(chain.proceed(request.withBearer(outcome.accessToken)))
            RefreshOutcome.SignedOut -> emit(first)
            RefreshOutcome.Unavailable -> emit(
                first.newBuilder()
                    .exception(ApolloNetworkException("登录态刷新失败:网络不可用"))
                    .build(),
            )
        }
    }

    private fun <D : Operation.Data> ApolloRequest<D>.withBearer(token: String?): ApolloRequest<D> =
        if (token.isNullOrEmpty()) this
        else newBuilder().addHttpHeader("Authorization", "Bearer $token").build()

    companion object {
        val AUTH_OPERATIONS = setOf("Login", "Register", "Refresh", "Logout")
    }
}
