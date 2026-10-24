package xyz.yophon.gopan.core.data.activity

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
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 协议层:operations 与 schema 对得上、筛选与撤回范围的变量、映射 */
class ApolloActivityRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repo: ApolloActivityRepository

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
        repo = ApolloActivityRepository(apollo)
    }

    @After
    fun tearDown() {
        apollo.close()
        server.shutdown()
    }

    private val nodeJson = """{"__typename":"Node","id":"f1","parentId":"dir","name":"b.txt","kind":"FILE","size":12,"mime":"text/plain",
        "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-21T00:00:00Z","deletedAt":null,"purgeAt":null,
        "subtreeBytes":null,"subtreeCount":null,"statsStale":false,"starred":false,"preview":{"__typename":"PreviewInfo","kind":"TEXT","thumbUrl":null}}"""

    private fun activityJson(id: String, kind: String = "WEBDAV", action: String = "RENAME", node: String = "null", extra: String = "") =
        """{"__typename":"Activity","id":"$id","at":"2026-10-21T10:00:00Z",
        "actor":{"__typename":"ActivityActor","kind":"$kind","ref":"ap-9","label":"rclone"},
        "batchId":"b1","action":"$action","nodeId":"f1","node":$node,"nodeKind":"FILE","name":"b.txt","parentPath":"/文档",
        "fromPath":null,"toPath":null,"oldName":"a.txt","newName":"b.txt","files":1,"bytes":12,"note":null,
        "undoOf":null,"undoneAt":null,"undoable":true$extra}"""

    @Test
    fun `list sends the filter only when there is one and maps rows`() = runBlocking<Unit> {
        handlers["Activities"] = {
            """{"data":{"activities":{"__typename":"ActivityPage","items":[
              ${activityJson("2", node = nodeJson)},
              ${activityJson("1", kind = "TELEPORTER", action = "LEVITATE")}
            ],"nextCursor":"c2"}}}"""
        }
        val page = repo.list(ActivityQuery(ActorKind.WEBDAV, "ap-9", "2026-10-21T00:00:00Z"), cursor = null)
        val vars = requests.single().second
        val filter = vars["filter"]!!.jsonObject
        assertThat(filter["actorKind"]!!.jsonPrimitive.content).isEqualTo("WEBDAV")
        assertThat(filter["actorRef"]!!.jsonPrimitive.content).isEqualTo("ap-9")
        assertThat(filter["since"]!!.jsonPrimitive.content).isEqualTo("2026-10-21T00:00:00Z")
        assertThat(vars["limit"]!!.jsonPrimitive.content).isEqualTo("50")
        assertThat(page.nextCursor).isEqualTo("c2")

        val (a2, a1) = page.items
        assertThat(a2.actor).isEqualTo(ActivityActor(ActorKind.WEBDAV, "ap-9", "rclone"))
        assertThat(a2.action).isEqualTo(ActivityAction.RENAME)
        assertThat(a2.oldName).isEqualTo("a.txt")
        assertThat(a2.node?.id).isEqualTo("f1")
        assertThat(a2.node?.parentId).isEqualTo("dir")
        assertThat(a2.node?.kind).isEqualTo(NodeKind.FILE)
        assertThat(a2.undoable).isTrue()
        assertThat(a2.undone).isFalse()
        // 新版服务端加的来源 / 操作:当作系统 / 改动
        assertThat(a1.actor.kind).isEqualTo(ActorKind.SYSTEM)
        assertThat(a1.action).isEqualTo(ActivityAction.OTHER)
        assertThat(a1.node).isNull()

        // 不筛:filter 不出现;没有 ref 的来源只传种类
        repo.list(ActivityQuery(), cursor = "c2")
        repo.list(ActivityQuery(ActorKind.IMG), cursor = null)
        assertThat(requests[1].second["filter"]).isAnyOf(null, JsonNull)
        assertThat(requests[1].second["cursor"]!!.jsonPrimitive.content).isEqualTo("c2")
        val imgFilter = requests[2].second["filter"]!!.jsonObject
        assertThat(imgFilter.keys).containsExactly("actorKind")
    }

    @Test
    fun `actors are mapped, unknown kinds dropped`() = runBlocking<Unit> {
        handlers["ActivityActors"] = {
            """{"data":{"activityActors":[
              {"__typename":"ActivityActorSummary","actor":{"__typename":"ActivityActor","kind":"ANDROID","ref":"fam","label":"安卓 App · Pixel 8"},"total":12,"lastAt":"2026-10-21T10:00:00Z"},
              {"__typename":"ActivityActorSummary","actor":{"__typename":"ActivityActor","kind":"FUTURE","ref":"x","label":"?"},"total":1,"lastAt":"2026-10-20T10:00:00Z"}
            ]}}"""
        }
        val actors = repo.actors()
        assertThat(actors).containsExactly(ActorSummary(ActivityActor(ActorKind.ANDROID, "fam", "安卓 App · Pixel 8"), 12, "2026-10-21T10:00:00Z"))
    }

    private val outcomeJson = """{"__typename":"UndoResult","done":1,"failed":1,"items":[
        {"__typename":"UndoItem","activity":{"__typename":"Activity","id":"2","name":"a.txt","action":"MOVE"},"ok":true,"reason":null},
        {"__typename":"UndoItem","activity":{"__typename":"Activity","id":"1","name":"b.txt","action":"RENAME"},"ok":false,"reason":"之后又改过名"}
      ]}"""

    @Test
    fun `preview and undo send exactly one kind of selection`() = runBlocking<Unit> {
        handlers["UndoPreview"] = { """{"data":{"undoPreview":$outcomeJson}}""" }
        handlers["UndoActivities"] = { """{"data":{"undoActivities":$outcomeJson}}""" }

        val preview = repo.preview(UndoTarget.Batch("b1"))
        assertThat(preview.done).isEqualTo(1)
        assertThat(preview.items[1]).isEqualTo(UndoItem("1", "b.txt", ActivityAction.RENAME, ok = false, reason = "之后又改过名"))
        assertThat(requests[0].second["selection"]!!.jsonObject.keys).containsExactly("batchId")

        repo.undo(UndoTarget.Ids(listOf("7")))
        val ids = requests[1].second["selection"]!!.jsonObject
        assertThat(ids.keys).containsExactly("ids")
        assertThat(ids["ids"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("7")

        repo.undo(UndoTarget.Filter(ActivityQuery(ActorKind.AGENT, "oauth:g1", "2026-10-21T00:00:00Z")))
        val filter = requests[2].second["selection"]!!.jsonObject["filter"]!!.jsonObject
        assertThat(filter["actorKind"]!!.jsonPrimitive.content).isEqualTo("AGENT")
        assertThat(filter["actorRef"]!!.jsonPrimitive.content).isEqualTo("oauth:g1")
        assertThat(requests.map { it.first }).containsExactly("UndoPreview", "UndoActivities", "UndoActivities").inOrder()
    }
}
