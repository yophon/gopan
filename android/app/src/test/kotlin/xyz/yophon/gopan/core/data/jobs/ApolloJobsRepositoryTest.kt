package xyz.yophon.gopan.core.data.jobs

import com.apollographql.apollo.ApolloClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 协议层:真正的 Apollo 客户端打 MockWebServer,验证 operations 与 schema 对得上、变量、映射与开关缓存 */
class ApolloJobsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repo: ApolloJobsRepository

    /** (operationName, variables) */
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
        repo = ApolloJobsRepository(apollo)
    }

    @After
    fun tearDown() {
        apollo.close()
        server.shutdown()
    }

    @Test
    fun `myJobs maps every field`() = runBlocking {
        handlers["MyJobs"] = {
            """{"data":{"myJobs":{"__typename":"JobPage","items":[
              ${jobJson("j1", "RUNNING", bytesTotal = "2048")},
              ${jobJson("j2", "DONE", kind = "EXTRACT_ARCHIVE", url = null, result = """{"__typename":"Node","id":"x1","name":"包","kind":"FOLDER","parentId":"dir"}""", errorCode = "\"GOT_HTML\"")}
            ],"nextCursor":null}}}"""
        }
        val jobs = repo.myJobs()
        assertThat(requests.single().second["active"]?.jsonPrimitive?.content).isEqualTo("false")
        val running = jobs[0]
        assertThat(running.kind).isEqualTo(JobKind.FETCH_URL)
        assertThat(running.status).isEqualTo(JobStatus.RUNNING)
        assertThat(running.title).isEqualTo("file.zip")
        assertThat(running.url).isEqualTo("https://example.com/j1.zip")
        assertThat(running.parentId).isEqualTo("dir")
        assertThat(running.bytesDone).isEqualTo(1024)
        assertThat(running.bytesTotal).isEqualTo(2048)
        assertThat(running.result).isNull()
        val done = jobs[1]
        assertThat(done.kind).isEqualTo(JobKind.EXTRACT_ARCHIVE)
        assertThat(done.url).isNull()
        assertThat(done.result).isEqualTo(JobResult("x1", "包", isFolder = true, parentId = "dir"))
        assertThat(done.errorCode).isEqualTo(GOT_HTML)
        assertThat(done.filesDone).isEqualTo(3)
        assertThat(done.filesTotal).isEqualTo(4)
        assertThat(done.filesSkipped).isEqualTo(1)
        assertThat(done.finishedAt).isEqualTo("2026-10-21T12:00:00Z")
    }

    @Test
    fun `fetchUrls sends the links and the folder`() = runBlocking {
        handlers["FetchUrls"] = { """{"data":{"fetchUrls":[${jobJson("n1", "QUEUED")}]}}""" }
        val created = repo.fetchUrls(listOf("https://a.com/1.zip", "https://b.com/2.zip"), "chat-folder")
        val vars = requests.single().second
        assertThat(vars["urls"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("https://a.com/1.zip", "https://b.com/2.zip").inOrder()
        assertThat(vars["parentId"]?.jsonPrimitive?.content).isEqualTo("chat-folder")
        assertThat(created.single().status).isEqualTo(JobStatus.QUEUED)
    }

    @Test
    fun `fetchEnabled is asked once per session and forgotten on sign-out`() = runBlocking {
        handlers["FetchEnabled"] = { """{"data":{"fetchEnabled":true}}""" }
        assertThat(repo.fetchEnabled()).isTrue()
        assertThat(repo.fetchEnabled()).isTrue()
        assertThat(requests.count { it.first == "FetchEnabled" }).isEqualTo(1)
        repo.clearMemory()
        handlers["FetchEnabled"] = { """{"data":{"fetchEnabled":false}}""" }
        assertThat(repo.fetchEnabled()).isFalse()
        assertThat(requests.count { it.first == "FetchEnabled" }).isEqualTo(2)
    }

    @Test
    fun `server error codes become GopanException codes`() = runBlocking {
        handlers["FetchUrls"] = {
            """{"errors":[{"message":"排队中的后台任务过多(最多 10 个),等前面的完成再来","path":["fetchUrls"],"extensions":{"code":"TOO_MANY_JOBS"}}],"data":null}"""
        }
        val e = runCatching { repo.fetchUrls(listOf("https://a.com/x"), null) }.exceptionOrNull() as GopanException
        assertThat(e.code).isEqualTo("TOO_MANY_JOBS")
        assertThat(JobErrors.messageFor(e)).isEqualTo("排队中的后台任务过多(最多 10 个),等前面的完成再来")
    }

    @Test
    fun `cancel, retry and clear`() = runBlocking {
        handlers["CancelJob"] = { """{"data":{"cancelJob":${jobJson(it["id"]!!.jsonPrimitive.content, "CANCELED")}}}""" }
        handlers["RetryJob"] = { """{"data":{"retryJob":${jobJson(it["id"]!!.jsonPrimitive.content, "QUEUED")}}}""" }
        handlers["ClearFinishedJobs"] = { """{"data":{"clearFinishedJobs":3}}""" }
        assertThat(repo.cancel("j1").status).isEqualTo(JobStatus.CANCELED)
        assertThat(repo.retry("j1").status).isEqualTo(JobStatus.QUEUED)
        assertThat(repo.clearFinished()).isEqualTo(3)
        assertThat(requests.map { it.first }).containsExactly("CancelJob", "RetryJob", "ClearFinishedJobs").inOrder()
    }

    private fun jobJson(
        id: String,
        status: String,
        kind: String = "FETCH_URL",
        url: String? = "https://example.com/$id.zip",
        bytesTotal: String = "null",
        result: String = "null",
        errorCode: String = "null",
    ): String = """{"__typename":"Job","id":"$id","kind":"$kind","status":"$status","title":"file.zip",
        "url":${url?.let { "\"$it\"" } ?: "null"},"parentId":"dir","bytesDone":1024,"bytesTotal":$bytesTotal,
        "filesDone":3,"filesTotal":4,"filesSkipped":1,"errorCode":$errorCode,"errorMessage":null,
        "createdAt":"2026-10-21T11:00:00Z","finishedAt":"2026-10-21T12:00:00Z","result":$result}"""
}
