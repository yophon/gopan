package xyz.yophon.gopan.core.assistant

import androidx.compose.runtime.Immutable
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import xyz.yophon.gopan.core.network.await
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 助手在 App 里的宿主(doc/33 八之 2):QuickJS 里跑网页同一份运行时(assets/assistant/runtime.js,
 * 由 web/scripts/build-assistant-runtime.mjs 生成),这里给它网络、随机数、等待,接住它交出来的事件与确认请求。
 *
 * - 网络走 OkHttp,没有跨域限制,所以直连、转发都行。同源(gopan 站点)的请求在这里加登录凭证,401 时换一张再试一次。
 * - 对话的状态以 [state] 给界面;确认卡在 [answer] 里答复;[stop] 让这一轮停下。
 * - 一个实例一个 QuickJS;对 JS 的调用排队,一次一个。
 *
 * 不依赖 Android,单测在电脑的 JVM 上直接跑真 QuickJS。
 */
class AssistantHost(
    private val origin: String,
    private val script: () -> String,
    http: OkHttpClient,
    private val auth: Auth,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** 登录凭证:[token] 现在的;[refresh] 401 之后换一张(拿不到给 null) */
    interface Auth {
        suspend fun token(): String?
        suspend fun refresh(stale: String?): String?
    }

    // 模型的回答是流式的,两块之间可能隔很久(想得久):读超时放宽
    private val client = http.newBuilder().readTimeout(java.time.Duration.ofMinutes(5)).build()
    private val calls = ConcurrentHashMap<Long, Open>()
    private val sleeps = ConcurrentHashMap<Long, Job>()
    private val random = SecureRandom()
    private val mutex = Mutex()
    private var js: QuickJs? = null

    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private var pendingApproval: CompletableDeferred<String>? = null
    private var stopSignal: CompletableDeferred<Boolean>? = null

    private class Open(val call: okhttp3.Call, var response: Response? = null)

    private suspend fun quickJs(): QuickJs {
        js?.let { return it }
        val q = QuickJs.create(dispatcher)
        q.asyncFunction("__hostFetch") { args -> fetch((args[0] as Number).toLong(), args[1] as String) }
        q.asyncFunction("__hostRead") { args -> read((args[0] as Number).toLong()) }
        q.function("__hostCancel") { args -> cancel((args[0] as Number).toLong()) }
        q.function("__hostRandom") { args -> ByteArray((args[0] as Number).toInt()).also(random::nextBytes) }
        q.asyncFunction("__hostSleep") { args -> sleep((args[0] as Number).toLong(), (args[1] as Number).toLong()) }
        q.function("__hostCancelSleep") { args -> sleeps.remove((args[0] as Number).toLong())?.cancel() }
        q.function("__hostIdle") { _ -> idle() }
        q.function("__hostEmit") { args -> onEvent(args[0] as String) }
        q.asyncFunction("__hostApprove") { args -> approve(args[0] as String) }
        q.asyncFunction("__hostWaitStop") { _ -> (stopSignal ?: CompletableDeferred(false)).await() }
        q.function("__hostTurnEnd") { _ -> stopSignal?.complete(false) }
        q.evaluate<Any?>(script(), "assistant-runtime.js", false)
        q.evaluate<Any?>("gopanAssistant.init(${jsString(origin)})", "init.js", false)
        js = q
        return q
    }

    private suspend fun call(code: String) = mutex.withLock { quickJs().evaluate<Any?>(code, "call.js", false) }

    // ---- 给界面的操作 ----

    /** 新对话 */
    suspend fun reset() {
        _state.value = AssistantState()
        call("gopanAssistant.reset()")
    }

    /** 打开一个旧对话 */
    suspend fun open(id: String) {
        _state.value = AssistantState(loading = true)
        try {
            call("gopanAssistant.open(${jsString(id)})")
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /** 发一句话,等这一轮回答完 */
    suspend fun send(args: SendArgs) {
        stopSignal = CompletableDeferred()
        _state.update { it.copy(running = true, error = null) }
        try {
            call("gopanAssistant.send(${jsString(json.encodeToString(SendArgs.serializer(), args))})")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            pendingApproval?.complete(DENY)
            stopSignal?.complete(false)
            _state.update { it.copy(running = false, live = "", approval = null) }
        }
    }

    /** 「停止」:让这一轮停下;等着的确认卡当「不行」 */
    fun stop() {
        stopSignal?.complete(true)
        pendingApproval?.complete(DENY)
    }

    /** 确认卡的答复:once / session / deny */
    fun answer(answer: String) {
        pendingApproval?.complete(answer)
    }

    /** 服务器上跑的一轮(doc/34)推来的事件:items / live / conversation / error 与本机跑时一样处理 */
    fun applyServerEvent(raw: String) = onEvent(raw)

    /** 引擎改状态(服务器上跑时的运行状态、确认卡) */
    fun update(transform: (AssistantState) -> AssistantState) = _state.update(transform)

    fun close() {
        stop()
        calls.values.forEach { it.call.cancel() }
        calls.clear()
        js?.close()
        js = null
        _state.value = AssistantState()
    }

    // ---- JS 交出来的事件 ----

    private fun onEvent(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "items" -> _state.update { it.copy(items = json.decodeFromJsonElement(ItemsEvent.serializer(), obj).items) }
            "live" -> _state.update { it.copy(live = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
            "conversation" -> _state.update { it.copy(conversation = json.decodeFromJsonElement(ConversationEvent.serializer(), obj).conversation) }
            "error" -> {
                val conflict = obj["conflict"]?.jsonPrimitive?.contentOrNull == "true"
                _state.update { it.copy(error = obj["message"]?.jsonPrimitive?.contentOrNull, conflict = it.conflict || conflict) }
            }
        }
    }

    private suspend fun approve(raw: String): String {
        val view = runCatching { json.decodeFromString(AssistantApproval.serializer(), raw) }.getOrNull() ?: return DENY
        val waiting = CompletableDeferred<String>()
        pendingApproval = waiting
        _state.update { it.copy(approval = view) }
        return try {
            waiting.await()
        } finally {
            pendingApproval = null
            _state.update { it.copy(approval = null) }
        }
    }

    // ---- 等待 ----

    /** 计时可以被 JS 取消(超时计时赢了就取消),不然 evaluate 会一直等着它 */
    private suspend fun sleep(id: Long, ms: Long) = coroutineScope {
        val job = launch { delay(ms) }
        sleeps[id] = job
        try {
            // 被取消时正常返回:JS 那边的计时已经按取消处理了,这里不能抛 CancellationException(会连带取消 evaluate)
            job.join()
        } finally {
            sleeps.remove(id)
        }
    }

    /** 一次调用结束:还挂着的计时与读取都清掉,evaluate 才会返回 */
    private fun idle() {
        sleeps.values.forEach { it.cancel() }
        sleeps.clear()
        calls.keys.toList().forEach(::cancel)
    }

    // ---- 网络 ----

    private fun isLocal(url: String) = url == origin || url.startsWith("$origin/") || url.startsWith("$origin?")

    private suspend fun fetch(id: Long, raw: String): String {
        val req = json.parseToJsonElement(raw).jsonObject
        val url = req["url"]!!.jsonPrimitive.content
        val method = req["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val headers = (req["headers"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        val body = req["body"]?.jsonPrimitive?.contentOrNull
        val local = isLocal(url)
        fun build(token: String?): okhttp3.Call {
            val b = Request.Builder().url(url)
            headers.forEach { (k, v) -> b.header(k, v) }
            if (local && token != null && headers.keys.none { it.equals("Authorization", true) }) b.header("Authorization", "Bearer $token")
            val media = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
            b.method(method, if (body != null || method == "POST") (body ?: "").toRequestBody(media) else null)
            return client.newCall(b.build())
        }
        var token = if (local) auth.token() else null
        var open = Open(build(token))
        calls[id] = open
        var res = open.call.await()
        if (local && res.code == 401) {
            res.close()
            token = auth.refresh(token)
            if (token != null && calls.containsKey(id)) {
                open = Open(build(token))
                calls[id] = open
                res = open.call.await()
            }
        }
        open.response = res
        val out = mutableMapOf<String, String>()
        for (name in res.headers.names()) out[name.lowercase()] = res.headers[name].orEmpty()
        return json.encodeToString(FetchMeta.serializer(), FetchMeta(res.code, out))
    }

    private suspend fun read(id: Long): ByteArray? = withContext(Dispatchers.IO) {
        val open = calls[id] ?: return@withContext null
        val source = open.response?.body?.source() ?: return@withContext null.also { finish(id) }
        val buf = Buffer()
        val n = source.read(buf, CHUNK)
        if (n < 0) {
            finish(id)
            null
        } else {
            buf.readByteArray()
        }
    }

    private fun cancel(id: Long) {
        calls[id]?.call?.cancel()
        finish(id)
    }

    private fun finish(id: Long) {
        calls.remove(id)?.response?.close()
    }

    companion object {
        const val DENY = "deny"
        private const val CHUNK = 16L * 1024
        internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        /** 字符串字面量:JSON 的字符串写法在 JS 里也合法 */
        fun jsString(s: String): String = json.encodeToString(String.serializer(), s)
    }
}

@Serializable
private data class FetchMeta(val status: Int, val headers: Map<String, String>)

@Serializable
private data class ItemsEvent(val items: List<AssistantItem>)

@Serializable
private data class ConversationEvent(val conversation: AssistantConversation)

/** 面板上的一条:user / assistant / tool / error;工具调用的 [text] 是一行人话 */
@Immutable
@Serializable
data class AssistantItem(val kind: String, val key: String, val text: String, val status: String? = null)

@Immutable
@Serializable
data class AssistantConversation(
    val id: String,
    val title: String,
    val providerId: String? = null,
    val model: String = "",
    val entries: Int = 0,
    val updatedAt: String = "",
)

/** 确认卡:[names] 是要动的东西(最多 20 个),还有 [more] 个没列 */
@Immutable
@Serializable
data class AssistantApproval(
    val title: String,
    val names: List<String> = emptyList(),
    val more: Int = 0,
    val detail: String? = null,
    val canAllowSession: Boolean = false,
)

/** 发一句话要的东西;provider 的 kind 是 GraphQL 的写法(OPENAI / ANTHROPIC …) */
@Serializable
data class SendArgs(
    val text: String,
    val context: String,
    val provider: Provider,
    val model: String,
    val mode: String,
) {
    @Serializable
    data class Provider(val id: String, val name: String, val kind: String, val baseUrl: String, val site: Boolean)
}

@Immutable
data class AssistantState(
    val items: List<AssistantItem> = emptyList(),
    val live: String = "",
    val running: Boolean = false,
    val loading: Boolean = false,
    val approval: AssistantApproval? = null,
    val error: String? = null,
    val conflict: Boolean = false,
    val conversation: AssistantConversation? = null,
    /** 这个对话交给服务器跑(doc/34) */
    val onServer: Boolean = false,
    /** 服务器上这个对话最近的一轮 */
    val runId: String? = null,
)
