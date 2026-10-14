package xyz.yophon.gopan.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.network.GopanCookieJar
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.await
import xyz.yophon.gopan.di.NoRedirect
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yophon ID 原生登录的第 3~5 步(纯 HTTP,可用 MockWebServer 测):
 * 3. POST {issuer}/api/mobile/ticket(Bearer 账号中心 access token)→ {ticket, entry};entry 必须等于 {base}/api/auth/id/mobile;
 * 4. POST entry 表单 ticket/verifier/return,不跟随跳转,期待 303 + Set-Cookie gopan_rt(CookieJar 自动收下);
 * 5. refresh 拿 access token → SignedIn,与密码登录殊途同归。
 */
@Singleton
class TicketExchange @Inject constructor(
    private val config: AppConfig,
    @NoRedirect private val httpClient: OkHttpClient,
    private val cookieJar: GopanCookieJar,
    private val sessionManager: SessionManager,
) {
    @Serializable
    data class Ticket(val ticket: String, val entry: String)

    @Serializable
    private data class TicketRequest(@SerialName("client_id") val clientId: String, val challenge: String)

    /** 账号中心的 access token 用完即丢,不要持久化。成功返回时 AuthState 已是 SignedIn。 */
    suspend fun signIn(idAccessToken: String) {
        sessionManager.beginSignIn()
        // 网络读写与 CookieJar(加密存储)都不能在主线程:调用方是 viewModelScope
        withContext(Dispatchers.IO) {
            val verifier = Pkce.generateVerifier()
            val ticket = requestTicket(idAccessToken, Pkce.challenge(verifier))
            exchange(ticket, verifier)
        }
        if (!sessionManager.refresh()) {
            throw GopanException(GopanErrors.ID_SESSION_FAILED, "登录成功但会话建立失败,请重试")
        }
    }

    internal suspend fun requestTicket(idAccessToken: String, challenge: String): Ticket = withContext(Dispatchers.IO) {
        requestTicketBlocking(idAccessToken, challenge)
    }

    private suspend fun requestTicketBlocking(idAccessToken: String, challenge: String): Ticket {
        val body = json.encodeToString(TicketRequest(config.ticketClientId, challenge))
        val request = Request.Builder()
            .url(config.ticketUrl)
            .header("Authorization", "Bearer $idAccessToken")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val ticket = call(request).use { response ->
            when (response.code) {
                200 -> Unit
                401 -> throw GopanException(GopanErrors.ID_AUTH_FAILED, "账号中心登录已失效,请重试")
                403 -> throw GopanException(GopanErrors.ID_TICKET_DENIED, "账号未授权或登录暂不可用")
                429 -> throw GopanException(GopanErrors.RATE_LIMITED, "尝试过于频繁,请稍后再试")
                else -> throw GopanException(GopanErrors.ID_BAD_RESPONSE, "账号中心响应异常(HTTP ${response.code})")
            }
            val text = response.body?.string().orEmpty()
            runCatching { json.decodeFromString<Ticket>(text) }.getOrElse {
                throw GopanException(GopanErrors.ID_BAD_RESPONSE, "账号中心响应无法解析")
            }
        }
        if (ticket.entry != config.mobileEntryUrl) {
            Timber.w("票据 entry 与服务器地址不一致,拒绝登录")
            throw GopanException(GopanErrors.ID_ENTRY_MISMATCH, "账号中心返回的入口与云盘地址不一致,已拒绝登录")
        }
        if (ticket.ticket.isBlank()) {
            throw GopanException(GopanErrors.ID_BAD_RESPONSE, "账号中心未返回票据")
        }
        return ticket
    }

    private suspend fun exchange(ticket: Ticket, verifier: String) {
        val form = FormBody.Builder()
            .add("ticket", ticket.ticket)
            .add("verifier", verifier)
            .add("return", "/drive")
            .build()
        // 用本地算出的 entry 而不是响应里的字符串:前面已经校验二者相等
        val request = Request.Builder().url(config.mobileEntryUrl).post(form).build()
        call(request).use { response ->
            when (response.code) {
                in 200..399 -> Unit
                // 票据一次性,重复使用也是 403;文案按产品决定统一
                403 -> throw GopanException(GopanErrors.ID_FORBIDDEN, "账号未授权或登录暂不可用")
                503 -> throw GopanException(GopanErrors.ID_UNAVAILABLE, "账号中心暂不可用,请稍后再试")
                else -> throw GopanException(GopanErrors.ID_EXCHANGE_FAILED, "登录失败(HTTP ${response.code})")
            }
        }
        if (!cookieJar.hasCookie(SessionManager.REFRESH_COOKIE)) {
            throw GopanException(GopanErrors.ID_NO_SESSION, "服务器未返回会话,请重试")
        }
    }

    private suspend fun call(request: Request) = try {
        httpClient.newCall(request).await()
    } catch (e: IOException) {
        throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e)
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}
