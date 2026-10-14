package xyz.yophon.gopan.testutil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模拟 gopan 服务端的 GraphQL 端点(按 operationName 分发)与票据交换入口。
 * - Login:下发 cookie gopan_rt=rt1,access token = [tokens] 第一枚;
 * - Refresh:要求 Cookie 里有 gopan_rt;[refreshOk] 为 false 时回 UNAUTHENTICATED;
 *   成功时轮换 cookie、发下一枚 token,可用 [refreshDelayMs] 放慢以制造并发;
 * - Me:Authorization 必须是当前有效 token([validToken]),否则 UNAUTHENTICATED;
 * - Logout:清 cookie。
 */
class FakeGopanServer : AutoCloseable {
    val server = MockWebServer()
    val refreshCount = AtomicInteger()
    val meCount = AtomicInteger()

    @Volatile var refreshOk = true
    @Volatile var refreshDelayMs = 0L
    @Volatile var validToken = "t1"
    @Volatile var nextRefreshToken = "t2"
    @Volatile var expectedCookie = "rt1"

    /** 票据交换入口(/api/auth/id/mobile)的行为 */
    @Volatile var mobileEntryStatus = 303
    @Volatile var expectedTicket: String? = null
    @Volatile var expectedChallenge: String? = null

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')
    val host: String get() = server.url("/").host

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/query" -> graphql(request)
                "/api/auth/id/mobile" -> mobileEntry(request)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private fun graphql(request: RecordedRequest): MockResponse {
        val body = request.body.readUtf8()
        val op = Json.parseToJsonElement(body).jsonObject["operationName"]?.jsonPrimitive?.content
        val auth = request.getHeader("Authorization")
        val cookie = request.getHeader("Cookie").orEmpty()
        return when (op) {
            "Login" -> MockResponse()
                .addHeader("Set-Cookie", setCookie("rt1"))
                .setBody(authPayload("login", "t1"))
            "Refresh" -> {
                refreshCount.incrementAndGet()
                if (refreshDelayMs > 0) Thread.sleep(refreshDelayMs)
                if (refreshOk && cookie.contains("gopan_rt=$expectedCookie")) {
                    val token = nextRefreshToken
                    validToken = token
                    expectedCookie = "rt-$token"
                    MockResponse()
                        .addHeader("Set-Cookie", setCookie("rt-$token"))
                        .setBody(authPayload("refresh", token))
                } else {
                    unauthenticated("refresh")
                }
            }
            "Me" -> {
                meCount.incrementAndGet()
                if (auth == "Bearer $validToken") MockResponse().setBody("""{"data":{"me":$USER_JSON}}""")
                else unauthenticated("me")
            }
            "Logout" -> MockResponse()
                .addHeader("Set-Cookie", "gopan_rt=; Path=/query; Max-Age=0; HttpOnly; SameSite=Lax")
                .setBody("""{"data":{"logout":true}}""")
            else -> MockResponse().setResponseCode(400).setBody("""{"errors":[{"message":"unknown operation $op"}]}""")
        }
    }

    private fun mobileEntry(request: RecordedRequest): MockResponse {
        val form = request.body.readUtf8().split("&").associate { pair ->
            val (k, v) = pair.split("=", limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
        val ticketOk = expectedTicket == null || form["ticket"] == expectedTicket
        val verifier = form["verifier"].orEmpty()
        val verifierOk = expectedChallenge == null || xyz.yophon.gopan.core.auth.Pkce.challenge(verifier) == expectedChallenge
        if (mobileEntryStatus != 303 || !ticketOk || !verifierOk) {
            return MockResponse().setResponseCode(if (mobileEntryStatus == 303) 403 else mobileEntryStatus)
                .setBody("账号未授权或登录暂不可用，请返回云盘重试")
        }
        return MockResponse()
            .setResponseCode(303)
            .addHeader("Location", "$baseUrl${form["return"] ?: "/drive"}")
            .addHeader("Set-Cookie", setCookie("rt1"))
    }

    private fun setCookie(value: String) = "gopan_rt=$value; Path=/query; Max-Age=1209600; HttpOnly; SameSite=Lax"

    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        server.shutdown()
    }

    companion object {
        const val USER_JSON =
            """{"__typename":"User","id":"u1","username":"alice","quotaBytes":1073741824,"usedBytes":12345,"isAdmin":false}"""

        fun authPayload(field: String, token: String) =
            """{"data":{"$field":{"__typename":"AuthPayload","accessToken":"$token","user":$USER_JSON}}}"""

        fun unauthenticated(path: String) = MockResponse().setBody(
            """{"errors":[{"message":"登录已失效","path":["$path"],"extensions":{"code":"UNAUTHENTICATED"}}],"data":null}""",
        )
    }
}
