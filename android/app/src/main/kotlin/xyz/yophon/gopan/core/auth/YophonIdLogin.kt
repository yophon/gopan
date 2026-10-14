package xyz.yophon.gopan.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Yophon ID 原生登录(AppAuth:Custom Tabs + 授权码 + PKCE)。
 *
 * 用法(登录页):
 * ```
 * val launcher = rememberYophonIdLauncher { data -> viewModel.onYophonIdResult(data) }
 * // 点击按钮:
 * viewModelScope.launch { launcher.launch(yophonIdLogin.buildAuthorizationIntent()) }
 * // 回调里:
 * viewModelScope.launch { yophonIdLogin.completeSignIn(data) }   // 成功后 AuthState 自动变为 SignedIn
 * ```
 * 账号中心的 access/refresh token 只在本次调用的内存里,不持久化、不复用。
 */
@Singleton
class YophonIdLogin @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: AppConfig,
    private val ticketExchange: TicketExchange,
) {
    /** debug 包对接本机 http 账号中心(E2E / 本地联调):见 [LoopbackConnections] */
    private val cleartextIssuer: Boolean by lazy { LoopbackConnections.allowsCleartext(config.idIssuer) }

    private val authService: AuthorizationService by lazy {
        if (cleartextIssuer) {
            val appAuthConfig = AppAuthConfiguration.Builder()
                .setConnectionBuilder(LoopbackConnections.builder)
                .setSkipIssuerHttpsCheck(true)
                .build()
            AuthorizationService(context, appAuthConfig)
        } else {
            AuthorizationService(context)
        }
    }

    /** 第 2 步:取发现文档、构造授权请求(PKCE S256 由 AppAuth 自动生成),返回可用 StartActivityForResult 启动的 Intent。 */
    suspend fun buildAuthorizationIntent(): Intent {
        val serviceConfig = fetchConfiguration()
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            config.idClientId,
            ResponseTypeValues.CODE,
            Uri.parse(config.idRedirectUri),
        )
            .setScope(SCOPES)
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /** 第 2(回调)~5 步:解析回调、换账号中心 access token、换票据、建立 gopan 会话。 */
    suspend fun completeSignIn(resultData: Intent?) {
        if (resultData == null) throw GopanException(GopanErrors.ID_CANCELLED, "登录已取消")
        val response = AuthorizationResponse.fromIntent(resultData)
        val error = AuthorizationException.fromIntent(resultData)
        if (response == null) {
            if (error == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW) {
                throw GopanException(GopanErrors.ID_CANCELLED, "登录已取消")
            }
            throw GopanException(
                GopanErrors.ID_AUTH_FAILED,
                "账号中心授权失败" + (error?.errorDescription?.let { ":$it" } ?: ""),
                cause = error,
            )
        }
        val token = exchangeCode(response)
        val idAccessToken = token.accessToken
            ?: throw GopanException(GopanErrors.ID_AUTH_FAILED, "账号中心未返回访问令牌")
        ticketExchange.signIn(idAccessToken)
    }

    private suspend fun fetchConfiguration(): AuthorizationServiceConfiguration =
        suspendCancellableCoroutine { cont ->
            val callback = AuthorizationServiceConfiguration.RetrieveConfigurationCallback { serviceConfig, ex ->
                if (serviceConfig != null) {
                    cont.resume(serviceConfig)
                } else {
                    cont.resumeWithException(
                        GopanException(GopanErrors.ID_DISCOVERY_FAILED, "无法连接账号中心,请检查网络", cause = ex),
                    )
                }
            }
            val issuer = Uri.parse(config.idIssuer)
            if (cleartextIssuer) {
                AuthorizationServiceConfiguration.fetchFromIssuer(issuer, callback, LoopbackConnections.builder)
            } else {
                AuthorizationServiceConfiguration.fetchFromIssuer(issuer, callback)
            }
        }

    private suspend fun exchangeCode(response: AuthorizationResponse): TokenResponse =
        suspendCancellableCoroutine { cont ->
            // 公开客户端:token_endpoint_auth_methods_supported 含 none
            authService.performTokenRequest(
                response.createTokenExchangeRequest(),
                NoClientAuthentication.INSTANCE,
            ) { tokenResponse, ex ->
                if (tokenResponse != null) {
                    cont.resume(tokenResponse)
                } else {
                    cont.resumeWithException(
                        GopanException(GopanErrors.ID_AUTH_FAILED, "账号中心换取令牌失败", cause = ex),
                    )
                }
            }
        }

    private companion object {
        const val SCOPES = "openid profile offline_access"
    }
}

/** 登录页用:启动 AppAuth 的授权 Activity,回调拿到结果 Intent(取消时可能为 null)。 */
@Composable
fun rememberYophonIdLauncher(onResult: (Intent?) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onResult(result.data)
    }
