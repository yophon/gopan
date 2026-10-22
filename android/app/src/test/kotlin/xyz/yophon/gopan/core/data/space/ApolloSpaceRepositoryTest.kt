package xyz.yophon.gopan.core.data.space

import com.apollographql.apollo.ApolloClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/** 协议层:operations 与 schema 对得上、变量、映射 */
class ApolloSpaceRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repo: ApolloSpaceRepository

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
        repo = ApolloSpaceRepository(apollo)
    }

    @After
    fun tearDown() {
        apollo.close()
        server.shutdown()
    }

    private fun node(id: String, kind: String = "FILE", size: String = "100", subtree: String = "null") =
        """{"__typename":"Node","id":"$id","parentId":null,"name":"$id","kind":"$kind","size":$size,"mime":null,
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z","deletedAt":null,"purgeAt":null,
        "subtreeBytes":$subtree,"subtreeCount":null,"statsStale":null,"preview":{"__typename":"PreviewInfo","kind":"NONE","thumbUrl":null}}"""

    private fun spaceFile(id: String, category: String = "VIDEO") =
        """{"__typename":"SpaceFile","node":${node(id)},"path":"/影视","category":"$category"}"""

    @Test
    fun `overview maps categories and drops unknown ones`() = runBlocking<Unit> {
        handlers["SpaceOverview"] = {
            """{"data":{"spaceOverview":{"__typename":"SpaceOverview","usedBytes":1000,"quotaBytes":5000,"trashFiles":2,"trashBytes":100,
            "byType":[{"__typename":"SpaceCategory","category":"VIDEO","files":3,"bytes":600},{"__typename":"SpaceCategory","category":"HOLOGRAM","files":1,"bytes":1}],
            "duplicateGroups":4,"duplicateBytes":70}}}"""
        }
        val o = repo.overview()
        assertThat(o.usedBytes).isEqualTo(1000)
        assertThat(o.byType).containsExactly(CategoryUsage(SpaceCategory.VIDEO, 3, 600))
        assertThat(o.duplicateGroups).isEqualTo(4)
        assertThat(o.duplicateBytes).isEqualTo(70)
    }

    @Test
    fun `folders, large and stale files`() = runBlocking<Unit> {
        handlers["SpaceFolders"] = {
            """{"data":{"spaceFolders":{"__typename":"SpaceFolderLevel","path":"/","folders":[${node("d1", "FOLDER", "null", "900")}],"looseFiles":2,"looseBytes":30}}}"""
        }
        handlers["SpaceLargeFiles"] = { """{"data":{"spaceLargeFiles":[${spaceFile("v1")},${spaceFile("x", "FUTURE")}]}}""" }
        handlers["SpaceStaleFiles"] = { """{"data":{"spaceStaleFiles":[${spaceFile("old")}]}}""" }
        handlers["SpaceEmptyFolders"] = { """{"data":{"spaceEmptyFolders":[]}}""" }

        val level = repo.folders(null)
        assertThat(requests[0].second["parentId"]).isEqualTo(JsonNull)
        assertThat(level.folders.single().subtreeBytes).isEqualTo(900)
        assertThat(level.folders.single().isFolder).isTrue()
        assertThat(level.looseBytes).isEqualTo(30)

        val large = repo.largeFiles(SpaceCategory.VIDEO)
        assertThat(requests[1].second["category"]!!.jsonPrimitive.content).isEqualTo("VIDEO")
        assertThat(requests[1].second["limit"]!!.jsonPrimitive.content).isEqualTo("100")
        assertThat(large.map { it.node.id }).containsExactly("v1", "x").inOrder()
        assertThat(large[0].category).isEqualTo(SpaceCategory.VIDEO)
        assertThat(large[0].path).isEqualTo("/影视")
        assertThat(large[1].category).isNull()

        repo.largeFiles(null)
        assertThat(requests[2].second.containsKey("category")).isFalse()

        assertThat(repo.staleFiles().single().node.id).isEqualTo("old")
        assertThat(repo.emptyFolders()).isEmpty()
    }

    @Test
    fun `duplicates page by offset`() = runBlocking<Unit> {
        handlers["SpaceDuplicates"] = {
            """{"data":{"spaceDuplicates":{"__typename":"DuplicatePage","items":[
              {"__typename":"DuplicateGroup","sha256":"abc","size":100,"copies":3,"files":[${spaceFile("a1")},${spaceFile("a2")},${spaceFile("a3")}]}
            ],"nextOffset":50}}}"""
        }
        val page = repo.duplicates(offset = 0)
        assertThat(requests[0].second["offset"]!!.jsonPrimitive.content).isEqualTo("0")
        assertThat(requests[0].second["limit"]!!.jsonPrimitive.content).isEqualTo("50")
        assertThat(page.nextOffset).isEqualTo(50)
        val g = page.items.single()
        assertThat(g.files.map { it.node.id }).containsExactly("a1", "a2", "a3").inOrder()
        assertThat(g.reclaimable).isEqualTo(200)
    }

    @Test
    fun `idle lists and revocations`() = runBlocking<Unit> {
        handlers["SpaceIdle"] = {
            """{"data":{"spaceIdle":{"__typename":"SpaceIdle",
              "shares":[{"__typename":"IdleShare","id":"s1","nodeId":"n1","name":"相册","kind":"FOLDER","expiresAt":"2026-01-01T00:00:00Z","createdAt":"2025-12-01T00:00:00Z","lastVisitAt":null,"expired":true}],
              "links":[{"__typename":"IdleLink","id":"l1","nodeId":"n2","name":"a.png","expiresAt":null,"createdAt":"2025-01-01T00:00:00Z","lastHitAt":null,"hits":0,"expired":false}],
              "requests":[{"__typename":"IdleRequest","id":"r1","title":"收作业","folderId":"f","expiresAt":"2026-09-01T00:00:00Z","createdAt":"2026-08-01T00:00:00Z"}]}}}"""
        }
        handlers["RevokeShare"] = { """{"data":{"revokeShare":true}}""" }
        handlers["RevokeLink"] = { """{"data":{"revokeLink":true}}""" }
        handlers["RevokeFileRequest"] = { """{"data":{"revokeFileRequest":true}}""" }

        val idle = repo.idle()
        assertThat(idle.shares.single().isFolder).isTrue()
        assertThat(idle.shares.single().expired).isTrue()
        assertThat(idle.links.single().hits).isEqualTo(0)
        assertThat(idle.requests.single().title).isEqualTo("收作业")
        assertThat(idle.isEmpty).isFalse()

        repo.revokeShare("s1")
        repo.revokeLink("l1")
        repo.revokeRequest("r1")
        assertThat(requests.drop(1).map { it.first to it.second["id"]!!.jsonPrimitive.content })
            .containsExactly("RevokeShare" to "s1", "RevokeLink" to "l1", "RevokeFileRequest" to "r1").inOrder()
    }
}
