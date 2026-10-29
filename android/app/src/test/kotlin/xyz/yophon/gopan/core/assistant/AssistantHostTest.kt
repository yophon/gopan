package xyz.yophon.gopan.core.assistant

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.rules.Timeout
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 助手在 App 里的宿主,跑的是真 QuickJS(-jvm 版)+ 真的运行时 assets/assistant/runtime.js。
 * 假的 gopan:GraphQL、MCP(建文件夹要确认)、经服务器转发的模型(第一轮要建文件夹,第二轮说完成)。
 */
class AssistantHostTest {
    // 卡住时失败而不是挂着(QuickJS 的调用停不下来时整个测试进程会一直等)
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private val json = Json { ignoreUnknownKeys = true }
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val modelCalls = AtomicInteger()
    private val appended = AtomicInteger()
    @Volatile private var current = "old" // 第一个同源请求带的旧凭证回 401,看换凭证重试

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                seen += request
                bodies += body
                val path = request.path.orEmpty()
                if (request.getHeader("Authorization") == "Bearer old") return MockResponse().setResponseCode(401)
                return when {
                    path == "/query" -> graphql(body)
                    path == "/mcp" -> mcp(body)
                    path.startsWith("/api/ai/p1/chat/completions") -> model()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }
    private val origin = server.url("").toString().trimEnd('/')

    private fun graphql(body: String): MockResponse {
        val q = json.parseToJsonElement(body).jsonObject["query"]!!.jsonPrimitive.content
        val data = when {
            "createAiConversation" in q -> """{"createAiConversation":{"id":"c1","title":"建个文件夹","providerId":"p1","model":"m","entries":0,"createdAt":"2026-10-29T00:00:00Z","updatedAt":"2026-10-29T00:00:00Z"}}"""
            "assistantAccess" in q -> """{"assistantAccess":{"disabled":false,"groups":[{"key":"files:read","scopes":["files:read","files:download"],"permission":"ALLOW"},{"key":"files:write","scopes":["files:write"],"permission":"ASK"}]}}"""
            "appendAiEntries" in q -> {
                val vars = json.parseToJsonElement(body).jsonObject["variables"]!!.jsonObject
                val total = vars["f"]!!.jsonPrimitive.content.toInt() + vars["e"]!!.jsonArray.size
                appended.incrementAndGet()
                """{"appendAiEntries":{"id":"c1","title":"建个文件夹","providerId":"p1","model":"m","entries":$total,"createdAt":"2026-10-29T00:00:00Z","updatedAt":"2026-10-29T00:00:01Z"}}"""
            }
            else -> return MockResponse().setBody("""{"errors":[{"message":"unexpected $q"}]}""")
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":$data}""")
    }

    private fun mcp(body: String): MockResponse {
        val msg = json.parseToJsonElement(body).jsonObject
        val id = msg["id"] ?: return MockResponse().setResponseCode(202)
        val result = when (msg["method"]!!.jsonPrimitive.content) {
            "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"gopan","version":"test"}}"""
            "tools/list" -> """{"tools":[
                {"name":"create_folder","description":"Create a folder","inputSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]},"annotations":{"destructiveHint":false},"_meta":{"gopan/scopes":["files:write"]}},
                {"name":"list_files","description":"List","inputSchema":{"type":"object","properties":{}},"annotations":{"readOnlyHint":true},"_meta":{"gopan/scopes":["files:read"]}}]}"""
            "tools/call" -> """{"content":[{"type":"text","text":"{\"id\":\"n1\",\"name\":\"报销\"}"}],"structuredContent":{"id":"n1","name":"报销"}}"""
            else -> "{}"
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }

    private fun sse(vararg chunks: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun model(): MockResponse = if (modelCalls.incrementAndGet() == 1) {
        sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"mcp__gopan__create_folder","arguments":"{\"name\":\"报销\"}"}}]},"finish_reason":"tool_calls"}]}""")
    } else {
        sse(
            """{"choices":[{"delta":{"content":"建好了「报销」"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":4}}""",
        )
    }

    private val host = AssistantHost(
        origin = origin,
        script = { File("src/main/assets/assistant/runtime.js").readText() },
        http = OkHttpClient(),
        auth = object : AssistantHost.Auth {
            override suspend fun token() = current
            override suspend fun refresh(stale: String?): String { current = "new"; return current }
        },
    )

    private suspend fun waitApproval(): AssistantApproval {
        repeat(1500) {
            host.state.value.approval?.let { return it }
            delay(20)
        }
        throw AssertionError(
            "no approval; state=${host.state.value} | requests=${seen.map { it.method + " " + it.path + " " + it.getHeader("Authorization") }}" +
                " | bodies=${bodies.map { it.take(200) }}",
        )
    }

    private suspend fun awaitTurn(turn: kotlinx.coroutines.Deferred<Unit>) {
        repeat(1500) {
            if (turn.isCompleted) return turn.await()
            delay(20)
        }
        throw AssertionError(
            "turn stuck; state=${host.state.value} | requests=${seen.map { it.method + " " + it.path }}" +
                " | bodies=${bodies.map { it.take(300) }}",
        )
    }

    @After
    fun tearDown() {
        host.close()
        server.shutdown()
    }

    private val args = SendArgs(
        text = "在根目录建个叫报销的文件夹",
        context = "用户正在看:/",
        provider = SendArgs.Provider(id = "p1", name = "DeepSeek", kind = "OPENAI", baseUrl = "https://api.example.com/v1", site = true),
        model = "m",
        mode = "proxy",
    )

    @Test
    fun runsATurnWithApprovalThroughTheServerProxy() = runBlocking {
        val turn = async { host.send(args) }
        val approval = waitApproval()
        assertThat(approval.title).isEqualTo("在根目录新建文件夹「报销」")
        assertThat(approval.canAllowSession).isTrue()
        host.answer("once")
        awaitTurn(turn)

        val state = host.state.value
        assertThat(state.error).isNull()
        assertThat(state.running).isFalse()
        assertThat(state.items.map { it.kind }).containsExactly("user", "tool", "assistant").inOrder()
        assertThat(state.items[1].text).isEqualTo("在根目录新建文件夹「报销」")
        assertThat(state.items[1].status).isEqualTo("ok")
        assertThat(state.items[2].text).isEqualTo("建好了「报销」")
        assertThat(state.conversation?.id).isEqualTo("c1")

        // 同源请求都换成了新凭证;/mcp 带着对话 id 与批次;模型请求没带真 key
        val after = seen.filter { it.getHeader("Authorization") == "Bearer new" }
        assertThat(after.map { it.path }).containsAtLeast("/query", "/mcp", "/api/ai/p1/chat/completions")
        val mcpCall = seen.first { it.path == "/mcp" && it.getHeader("X-Gopan-Assistant") != null }
        assertThat(mcpCall.getHeader("X-Gopan-Assistant")).isEqualTo("c1")
        assertThat(mcpCall.getHeader("X-Gopan-Turn")).isNotEmpty()
        assertThat(modelCalls.get()).isEqualTo(2)
        assertThat(appended.get()).isAtLeast(1)
    }

    @Test
    fun deniedApprovalLeavesTheFolderAlone() = runBlocking {
        val turn = async { host.send(args) }
        waitApproval()
        host.answer("deny")
        awaitTurn(turn)
        val tool = host.state.value.items.first { it.kind == "tool" }
        assertThat(tool.status).isEqualTo("denied")
        assertThat(bodies.none { it.contains("\"tools/call\"") }).isTrue()
    }

    @Test
    fun stopEndsTheTurnWhileWaitingForApproval() = runBlocking {
        val turn = async { host.send(args) }
        waitApproval()
        host.stop()
        awaitTurn(turn)
        assertThat(host.state.value.running).isFalse()
        assertThat(host.state.value.approval).isNull()
    }
}

@Suppress("unused")
private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content
