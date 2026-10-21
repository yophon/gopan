package xyz.yophon.gopan.core.data.notifications

import com.apollographql.apollo.ApolloClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 协议层:operations 与 schema 对得上、变量、映射;未读角标的本地计数 */
class ApolloNotificationsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repo: ApolloNotificationsRepository

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
        repo = ApolloNotificationsRepository(apollo)
    }

    @After
    fun tearDown() {
        apollo.close()
        server.shutdown()
    }

    @Test
    fun `list maps levels, targets and merge counts`() = runBlocking {
        handlers["Notifications"] = {
            """{"data":{"notifications":{"__typename":"NotificationPage","items":[
              ${json("n1", level = "ALERT", count = 3, target = """{"__typename":"NotificationTarget","kind":"NODE","id":"f1","parentId":null}""")},
              ${json("n2", level = "WARN", read = true, target = """{"__typename":"NotificationTarget","kind":"CREDENTIALS","id":"webdav","parentId":null}""")},
              ${json("n3", level = "INFO", target = "null")},
              ${json("n4", level = "FUTURE_LEVEL", target = """{"__typename":"NotificationTarget","kind":"SOMETHING_NEW","id":"x","parentId":null}""")}
            ],"nextCursor":"next"}}}"""
        }
        val page = repo.list(cursor = null, unreadOnly = true)
        val vars = requests.single().second
        assertThat(vars["cursor"]).isEqualTo(JsonNull)
        assertThat(vars["unreadOnly"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(page.nextCursor).isEqualTo("next")
        val (n1, n2, n3, n4) = page.items
        assertThat(n1.level).isEqualTo(NotificationLevel.ALERT)
        assertThat(n1.count).isEqualTo(3)
        assertThat(n1.target).isEqualTo(NotificationTarget(NotificationTargetKind.NODE, "f1", null))
        assertThat(n1.updatedAt).isEqualTo("2026-10-21T10:30:00Z")
        assertThat(n1.read).isFalse()
        assertThat(n2.read).isTrue()
        assertThat(n2.target).isEqualTo(NotificationTarget(NotificationTargetKind.CREDENTIALS, "webdav"))
        assertThat(n3.target).isNull()
        // 新版服务端加的级别 / 目标:当作提示、没有目标
        assertThat(n4.level).isEqualTo(NotificationLevel.INFO)
        assertThat(n4.target).isNull()
    }

    @Test
    fun `mark read sends null for all and a list for some`() = runBlocking {
        handlers["MarkNotificationsRead"] = { vars ->
            val ids = vars["ids"]
            """{"data":{"markNotificationsRead":${if (ids == null || ids == JsonNull) 9 else ids.jsonArray.size}}}"""
        }
        assertThat(repo.markRead(null)).isEqualTo(9)
        assertThat(repo.markRead(listOf("a", "b"))).isEqualTo(2)
        assertThat(requests[0].second["ids"]).isEqualTo(JsonNull)
        assertThat(requests[1].second["ids"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `clear and unread count`() = runBlocking {
        handlers["ClearNotifications"] = { """{"data":{"clearNotifications":4}}""" }
        handlers["UnreadNotifications"] = { """{"data":{"me":{"__typename":"User","unreadNotifications":12}}}""" }
        assertThat(repo.clear()).isEqualTo(4)
        assertThat(repo.unreadCount()).isEqualTo(12)
    }

    @Test
    fun `unread badge keeps the last value when refresh fails`() = runBlocking {
        val unread = UnreadNotifications(repo)
        handlers["UnreadNotifications"] = { """{"data":{"me":{"__typename":"User","unreadNotifications":5}}}""" }
        assertThat(unread.refresh()).isEqualTo(5)
        assertThat(unread.count.value).isEqualTo(5)
        unread.decrement()
        assertThat(unread.count.value).isEqualTo(4)
        handlers.remove("UnreadNotifications")
        assertThat(unread.refresh()).isNull()
        assertThat(unread.count.value).isEqualTo(4)
        unread.decrement(10)
        assertThat(unread.count.value).isEqualTo(0)
        unread.set(3)
        unread.clear()
        assertThat(unread.count.value).isEqualTo(0)
    }

    private fun json(id: String, level: String, count: Int = 1, read: Boolean = false, target: String): String =
        """{"__typename":"Notification","id":"$id","kind":"job_done","level":"$level","title":"标题","body":"正文","count":$count,
        "target":$target,"createdAt":"2026-10-21T10:00:00Z","updatedAt":"2026-10-21T10:30:00Z","read":$read}"""
}
