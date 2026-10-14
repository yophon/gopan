package xyz.yophon.gopan.core.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.testutil.FakeGopanServer
import xyz.yophon.gopan.testutil.TestHarness

/** 第 3~5 步:假账号中心发票据,假 gopan 用 303 + Set-Cookie 建会话,随后 refresh 成功。 */
class TicketExchangeTest {
    private lateinit var gopan: FakeGopanServer
    private lateinit var issuer: MockWebServer
    private lateinit var h: TestHarness
    private lateinit var exchange: TicketExchange

    /** 账号中心返回的 entry;默认指向假 gopan */
    private var entry: String = ""
    private var ticketStatus = 200
    private var lastChallenge: String? = null
    private var lastAuthHeader: String? = null
    private var lastClientId: String? = null

    @Before
    fun setUp() {
        gopan = FakeGopanServer()
        issuer = MockWebServer()
        issuer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/api/mobile/ticket") return MockResponse().setResponseCode(404)
                lastAuthHeader = request.getHeader("Authorization")
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                lastClientId = body["client_id"]?.jsonPrimitive?.content
                lastChallenge = body["challenge"]?.jsonPrimitive?.content
                gopan.expectedChallenge = lastChallenge
                gopan.expectedTicket = "tk1"
                if (ticketStatus != 200) return MockResponse().setResponseCode(ticketStatus).setBody("""{"error":"access_denied"}""")
                return MockResponse().setBody("""{"ticket":"tk1","entry":"$entry"}""")
            }
        }
        issuer.start()
        h = TestHarness(gopan, idIssuer = issuer.url("/").toString().trimEnd('/'))
        entry = "${gopan.baseUrl}/api/auth/id/mobile"
        exchange = TicketExchange(h.config, h.noRedirectOkHttp, h.cookieJar, h.sessionManager)
    }

    @After
    fun tearDown() {
        h.close()
        issuer.shutdown()
        gopan.close()
    }

    @Test
    fun `ticket then entry 303 then refresh ends SignedIn`() = runBlocking {
        exchange.signIn("id-access-token")

        assertThat(lastAuthHeader).isEqualTo("Bearer id-access-token")
        assertThat(lastClientId).isEqualTo("gopan")
        assertThat(lastChallenge).matches("^[A-Za-z0-9_-]{43}$")

        val entryRequest = gopan.server.takeRequest()
        assertThat(entryRequest.path).isEqualTo("/api/auth/id/mobile")
        assertThat(entryRequest.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded")

        val refresh = gopan.server.takeRequest()
        assertThat(refresh.path).isEqualTo("/query")
        assertThat(refresh.getHeader("Cookie")).contains("gopan_rt=rt1")

        assertThat(h.store.state.value).isInstanceOf(AuthState.SignedIn::class.java)
        assertThat((h.store.state.value as AuthState.SignedIn).user.username).isEqualTo("alice")
        assertThat(h.store.accessToken).isEqualTo("t2")
    }

    @Test
    fun `entry that does not match the configured base url is rejected before any exchange`() = runBlocking {
        entry = "https://evil.example/api/auth/id/mobile"

        val error = runCatching { exchange.signIn("id-access-token") }.exceptionOrNull()

        assertThat(error).isInstanceOf(GopanException::class.java)
        assertThat((error as GopanException).code).isEqualTo(GopanErrors.ID_ENTRY_MISMATCH)
        assertThat(gopan.server.requestCount).isEqualTo(0)
        assertThat(h.cookieJar.hasCookie("gopan_rt")).isFalse()
        assertThat(h.store.state.value).isNotInstanceOf(AuthState.SignedIn::class.java)
    }

    @Test
    fun `entry 403 maps to the unauthorized message`() = runBlocking {
        gopan.mobileEntryStatus = 403

        val error = runCatching { exchange.signIn("id-access-token") }.exceptionOrNull() as GopanException

        assertThat(error.code).isEqualTo(GopanErrors.ID_FORBIDDEN)
        assertThat(error.message).isEqualTo("账号未授权或登录暂不可用")
        assertThat(h.cookieJar.hasCookie("gopan_rt")).isFalse()
    }

    @Test
    fun `ticket endpoint 403 is reported without touching gopan`() = runBlocking {
        ticketStatus = 403

        val error = runCatching { exchange.signIn("id-access-token") }.exceptionOrNull() as GopanException

        assertThat(error.code).isEqualTo(GopanErrors.ID_TICKET_DENIED)
        assertThat(gopan.server.requestCount).isEqualTo(0)
    }
}
