package xyz.yophon.gopan.core.data.feeds

import com.apollographql.apollo.ApolloClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import xyz.yophon.gopan.core.data.clips.ApolloClipsRepository
import xyz.yophon.gopan.core.data.clips.ClipSubmission
import xyz.yophon.gopan.core.data.jobs.JobKind
import xyz.yophon.gopan.core.data.jobs.JobStatus
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 协议层:真正的 Apollo 客户端打 MockWebServer,验证订阅与剪藏的 operations、变量与映射 */
class ApolloFeedsClipsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var feeds: ApolloFeedsRepository
    private lateinit var clips: ApolloClipsRepository

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
        feeds = ApolloFeedsRepository(apollo)
        clips = ApolloClipsRepository(apollo)
    }

    @After
    fun tearDown() {
        apollo.close()
        server.shutdown()
    }

    private fun feedJson(
        id: String = "f1",
        kind: String = "RSS",
        mode: String = "DOWNLOAD",
        folder: String? = """{"__typename":"Node","id":"dir","name":"播客"}""",
        lastError: String? = null,
        enabled: Boolean = true,
    ) = """{"__typename":"Feed","id":"$id","kind":"$kind","url":"https://example.com/feed.xml","title":"播客","siteUrl":"https://example.com",
        "folder":$folder,"mode":"$mode","includeWords":"a,b","excludeWords":"","assetPattern":"","prerelease":false,"keepLast":3,
        "intervalMinutes":720,"enabled":$enabled,"lastCheckedAt":"2026-10-21T10:00:00Z","lastOkAt":null,
        "lastError":${lastError?.let { "\"$it\"" } ?: "null"},"failCount":0,"nextCheckAt":"2026-10-21T22:00:00Z",
        "createdAt":"2026-10-01T00:00:00Z","itemCount":12,"newCount":2,"latestAt":"2026-10-20T08:00:00Z"}"""

    private fun clipJson(id: String = "c1", node: String? = """{"__typename":"Node","id":"md1","name":"好文章.md","parentId":"dir"}""") =
        """{"__typename":"Clip","id":"$id","url":"https://example.com/a","title":"好文章","site":"示例","byline":"","excerpt":"摘要",
        "publishedAt":null,"node":$node,"words":1200,"images":3,"readAt":null,"createdAt":"2026-10-20T12:00:00Z"}"""

    private fun jobJson(status: String) =
        """{"__typename":"Job","id":"j1","kind":"CLIP_URL","status":"$status","title":"example.com","url":"https://example.com/a","parentId":null,
        "bytesDone":0,"bytesTotal":null,"filesDone":0,"filesTotal":null,"filesSkipped":0,"errorCode":null,"errorMessage":null,
        "createdAt":"2026-10-21T12:00:00Z","finishedAt":null,"result":null}"""

    @Test
    fun `feeds map every field`() = runBlocking {
        handlers["Feeds"] = { """{"data":{"feeds":[${feedJson()},${feedJson(id = "f2", kind = "GITHUB", folder = "null", lastError = "GitHub 限流了", enabled = false)}]}}""" }
        val list = feeds.feeds()
        val a = list[0]
        assertThat(a.kind).isEqualTo(FeedKind.RSS)
        assertThat(a.mode).isEqualTo(FeedMode.DOWNLOAD)
        assertThat(a.folderId).isEqualTo("dir")
        assertThat(a.folderName).isEqualTo("播客")
        assertThat(a.keepLast).isEqualTo(3)
        assertThat(a.intervalMinutes).isEqualTo(720)
        assertThat(a.newCount).isEqualTo(2)
        assertThat(a.lastError).isNull()
        val b = list[1]
        assertThat(b.kind).isEqualTo(FeedKind.GITHUB)
        assertThat(b.folderId).isNull()
        assertThat(b.lastError).isEqualTo("GitHub 限流了")
        assertThat(b.enabled).isFalse()
    }

    @Test
    fun `pausing sends the current settings along`() = runBlocking {
        handlers["Feeds"] = { """{"data":{"feeds":[${feedJson()}]}}""" }
        handlers["UpdateFeed"] = { """{"data":{"updateFeed":${feedJson(enabled = false)}}}""" }
        val feed = feeds.feeds().single()
        val updated = feeds.setEnabled(feed, enabled = false)
        assertThat(updated.enabled).isFalse()
        val (op, vars) = requests.last()
        assertThat(op).isEqualTo("UpdateFeed")
        assertThat(vars["id"]?.jsonPrimitive?.content).isEqualTo("f1")
        val input = vars["input"]!!.jsonObject
        assertThat(input["enabled"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(input["mode"]?.jsonPrimitive?.content).isEqualTo("DOWNLOAD")
        assertThat(input["folderId"]?.jsonPrimitive?.content).isEqualTo("dir")
        assertThat(input["includeWords"]?.jsonPrimitive?.content).isEqualTo("a,b")
        assertThat(input["keepLast"]?.jsonPrimitive?.content).isEqualTo("3")
        assertThat(input["intervalMinutes"]?.jsonPrimitive?.content).isEqualTo("720")
        assertThat(input.containsKey("url")).isFalse()
    }

    @Test
    fun `create sends url and mode, items map state and file`() = runBlocking {
        handlers["CreateFeed"] = { """{"data":{"createFeed":${feedJson(kind = "GITHUB")}}}""" }
        handlers["FeedItems"] = {
            """{"data":{"feedItems":{"__typename":"FeedItemPage","items":[
              {"__typename":"FeedItem","id":"i1","title":"v1.2.0/app.apk","url":"https://github.com/o/r/releases/tag/v1.2.0","enclosureUrl":"https://github.com/x.apk",
               "enclosureType":"application/vnd.android.package-archive","enclosureSize":5000000,"publishedAt":"2026-10-20T00:00:00Z","state":"DONE","error":null,
               "node":{"__typename":"Node","id":"n1","name":"app.apk","kind":"FILE","parentId":"tag"},"jobId":"j9","createdAt":"2026-10-20T01:00:00Z"},
              {"__typename":"FeedItem","id":"i2","title":"旧的","url":"","enclosureUrl":"","enclosureType":"","enclosureSize":null,"publishedAt":null,
               "state":"SKIPPED","error":null,"node":null,"jobId":null,"createdAt":"2026-10-19T01:00:00Z"}
            ],"nextCursor":"next"}}}"""
        }
        feeds.create("owner/repo", FeedMode.DOWNLOAD, " *.apk ")
        val input = requests.last().second["input"]!!.jsonObject
        assertThat(input["url"]?.jsonPrimitive?.content).isEqualTo("owner/repo")
        assertThat(input["mode"]?.jsonPrimitive?.content).isEqualTo("DOWNLOAD")
        assertThat(input["assetPattern"]?.jsonPrimitive?.content).isEqualTo("*.apk")
        // 其余用服务端默认值
        assertThat(input.keys).containsExactly("url", "mode", "assetPattern")

        val page = feeds.items("f1", null)
        assertThat(page.nextCursor).isEqualTo("next")
        assertThat(page.items[0].state).isEqualTo(FeedItemState.DONE)
        assertThat(page.items[0].node).isEqualTo(FeedItemNode("n1", "app.apk", isFolder = false, parentId = "tag"))
        assertThat(page.items[0].enclosureSize).isEqualTo(5_000_000)
        assertThat(page.items[1].state).isEqualTo(FeedItemState.SKIPPED)
        assertThat(page.items[1].node).isNull()
    }

    @Test
    fun `clipUrl gives the existing clip or the new job`() = runBlocking {
        handlers["ClipUrl"] = { vars ->
            if (vars["force"]?.jsonPrimitive?.content == "true") {
                """{"data":{"clipUrl":{"__typename":"ClipResult","job":${jobJson("QUEUED")},"existing":null}}}"""
            } else {
                """{"data":{"clipUrl":{"__typename":"ClipResult","job":null,"existing":${clipJson()}}}}"""
            }
        }
        val existing = clips.clipUrl("https://example.com/a", force = false) as ClipSubmission.Existing
        assertThat(existing.clip.nodeId).isEqualTo("md1")
        assertThat(existing.clip.unread).isTrue()
        assertThat(existing.clip.words).isEqualTo(1200)
        val queued = clips.clipUrl("https://example.com/a", force = true) as ClipSubmission.Queued
        assertThat(queued.job.kind).isEqualTo(JobKind.CLIP_URL)
        assertThat(queued.job.status).isEqualTo(JobStatus.QUEUED)
        assertThat(requests.last().second["url"]?.jsonPrimitive?.content).isEqualTo("https://example.com/a")
    }

    @Test
    fun `nodeClip is null for plain markdown, job polling maps the job`() = runBlocking {
        handlers["NodeClip"] = { vars ->
            if (vars["nodeId"]?.jsonPrimitive?.content == "md1") """{"data":{"nodeClip":${clipJson()}}}""" else """{"data":{"nodeClip":null}}"""
        }
        handlers["ClipJob"] = { """{"data":{"job":${jobJson("RUNNING")}}}""" }
        assertThat(clips.nodeClip("md1")?.id).isEqualTo("c1")
        assertThat(clips.nodeClip("other")).isNull()
        assertThat(clips.job("j1").status).isEqualTo(JobStatus.RUNNING)
    }
}
