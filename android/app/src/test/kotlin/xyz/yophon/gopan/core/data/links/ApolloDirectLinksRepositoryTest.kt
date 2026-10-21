package xyz.yophon.gopan.core.data.links

import com.apollographql.apollo.ApolloClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 协议层:真正的 Apollo 客户端打 MockWebServer,验证 operations 与 schema 对得上、变量、映射与开关缓存 */
class ApolloDirectLinksRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repo: ApolloDirectLinksRepository

    private val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val handlers = ConcurrentHashMap<String, (JsonObject) -> String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val op = body["operationName"]?.jsonPrimitive?.content.orEmpty()
                val variables = body["variables"]?.jsonObject ?: JsonObject(emptyMap())
                requests += op to variables
                val handler = handlers[op] ?: return MockResponse().setResponseCode(400)
                return MockResponse().setHeader("Content-Type", "application/json").setBody(handler(variables))
            }
        }
        server.start()
        apollo = buildApolloClient(server.url("/query").toString(), buildOkHttpClient(CookieJar.NO_COOKIES))
        val config = AppConfig(baseUrl = "https://drive.example.com/", idIssuer = "https://id.example.com", idClientId = "c", idRedirectUri = "x:/cb")
        repo = ApolloDirectLinksRepository(apollo, config)
    }

    @After
    fun tearDown() {
        apollo.close()
        server.shutdown()
    }

    @Test
    fun `myLinks maps every field and prefixes relative urls`() = runBlocking {
        handlers["MyLinks"] = {
            """{"data":{"myLinks":{"__typename":"DirectLinkPage","items":[
              ${linkJson("l1", url = "/x/AbC/猫.jpg", status = "PAUSED", pauseReason = "\"daily_cap\"", hasGps = true, lastHitAt = "\"2026-10-21T09:00:00Z\"")},
              ${linkJson("l2", url = "https://img.example.com/x/QwE/a.pdf", mime = "\"application/pdf\"")}
            ],"nextCursor":"c2","total":7}}}"""
        }
        val page = repo.myLinks(cursor = null)
        assertThat(requests.single().second["cursor"]).isEqualTo(JsonNull)
        assertThat(page.total).isEqualTo(7)
        assertThat(page.nextCursor).isEqualTo("c2")
        val first = page.items[0]
        assertThat(first.url).isEqualTo("https://drive.example.com/x/AbC/猫.jpg")
        assertThat(first.status).isEqualTo(LinkStatus.PAUSED)
        assertThat(first.pauseReason).isEqualTo("daily_cap")
        assertThat(first.hasGps).isTrue()
        assertThat(first.today).isEqualTo(LinkUsage(3, 3072, 1))
        assertThat(first.last30Days).isEqualTo(LinkUsage(40, 40960, 2))
        assertThat(first.hitsTotal).isEqualTo(50)
        assertThat(first.lastHitAt).isEqualTo("2026-10-21T09:00:00Z")
        assertThat(first.nodeId).isEqualTo("node-l1")
        assertThat(page.items[1].url).isEqualTo("https://img.example.com/x/QwE/a.pdf")
        assertThat(page.items[1].mime).isEqualTo("application/pdf")
    }

    @Test
    fun `unknown statuses become UNAVAILABLE`() = runBlocking {
        handlers["DirectLink"] = { """{"data":{"link":${linkJson(it["id"]!!.jsonPrimitive.content, status = "SOMETHING_NEW")}}}""" }
        assertThat(repo.link("l9").status).isEqualTo(LinkStatus.UNAVAILABLE)
    }

    @Test
    fun `create dedupes and splits into batches of 100`() = runBlocking {
        handlers["CreateLinks"] = { vars ->
            val ids = vars["nodeIds"]!!.jsonArray.map { it.jsonPrimitive.content }
            """{"data":{"createLinks":[${ids.joinToString(",") { linkJson("L$it", nodeId = it) }}]}}"""
        }
        val ids = (1..150).map { "n$it" } + "n1"
        val links = repo.create(ids)
        assertThat(links).hasSize(150)
        val batches = requests.filter { it.first == "CreateLinks" }.map { it.second["nodeIds"]!!.jsonArray.size }
        assertThat(batches).containsExactly(100, 50).inOrder()
    }

    @Test
    fun `pause, resume and revoke send the right variables`() = runBlocking {
        handlers["PauseLink"] = { vars ->
            val paused = vars["paused"]!!.jsonPrimitive.content.toBoolean()
            """{"data":{"pauseLink":${linkJson(vars["id"]!!.jsonPrimitive.content, status = if (paused) "PAUSED" else "ACTIVE", pauseReason = if (paused) "\"manual\"" else "null")}}}"""
        }
        handlers["RevokeLink"] = { """{"data":{"revokeLink":true}}""" }
        assertThat(repo.pause("l1", true).status).isEqualTo(LinkStatus.PAUSED)
        assertThat(repo.pause("l1", false).status).isEqualTo(LinkStatus.ACTIVE)
        repo.revoke("l1")
        assertThat(requests.map { it.first }).containsExactly("PauseLink", "PauseLink", "RevokeLink").inOrder()
        assertThat(requests.last().second["id"]!!.jsonPrimitive.content).isEqualTo("l1")
    }

    @Test
    fun `linksEnabled is asked once per session and forgotten on sign-out`() = runBlocking {
        handlers["LinksEnabled"] = { """{"data":{"linksEnabled":true}}""" }
        assertThat(repo.enabled()).isTrue()
        assertThat(repo.enabled()).isTrue()
        assertThat(requests.count { it.first == "LinksEnabled" }).isEqualTo(1)
        repo.clearMemory()
        handlers["LinksEnabled"] = { """{"data":{"linksEnabled":false}}""" }
        assertThat(repo.enabled()).isFalse()
        assertThat(requests.count { it.first == "LinksEnabled" }).isEqualTo(2)
    }

    @Test
    fun `server errors become GopanException codes`() = runBlocking {
        handlers["RevokeLink"] = {
            """{"errors":[{"message":"对象不存在或已被删除","path":["revokeLink"],"extensions":{"code":"NOT_FOUND"}}],"data":null}"""
        }
        val e = runCatching { repo.revoke("gone") }.exceptionOrNull() as GopanException
        assertThat(e.code).isEqualTo("NOT_FOUND")
    }

    private fun linkJson(
        id: String,
        url: String = "/x/T$id/$id.png",
        nodeId: String = "node-$id",
        status: String = "ACTIVE",
        pauseReason: String = "null",
        mime: String = "\"image/png\"",
        hasGps: Boolean = false,
        lastHitAt: String = "null",
    ): String = """{"__typename":"DirectLink","id":"$id","token":"T$id","path":"/x/T$id/$id.png","url":"$url","nodeId":"$nodeId",
        "name":"$id.png","mime":$mime,"size":2048,"status":"$status","pauseReason":$pauseReason,"source":"android",
        "createdAt":"2026-10-20T08:00:00Z","expiresAt":null,"hasGps":$hasGps,"hitsTotal":50,"bytesTotal":51200,"lastHitAt":$lastHitAt,
        "today":{"__typename":"LinkUsage","hits":3,"bytes":3072,"blocked":1},
        "last30Days":{"__typename":"LinkUsage","hits":40,"bytes":40960,"blocked":2}}"""
}
