package xyz.yophon.gopan.core.assistant

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.SignOutHook
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.yophon.gopan.core.network.Change
import xyz.yophon.gopan.core.network.ChangeFeed
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 里的助手(doc/33 八之 2):整个 App 一份,离开助手页面这一轮也接着跑完(和网页的面板一样)。
 * QuickJS 用到时才起;登出时关掉。
 */
@Singleton
class AssistantEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val config: AppConfig,
    private val session: SessionStore,
    private val refresher: dagger.Lazy<TokenRefresher>,
    private val repo: AssistantRepository,
    changes: ChangeFeed,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hostFlow = MutableStateFlow<AssistantHost?>(null)
    private var turn: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<AssistantState> = hostFlow
        .flatMapLatest { it?.state ?: MutableStateFlow(AssistantState()).asStateFlow() }
        .stateIn(scope, SharingStarted.Eagerly, AssistantState())

    private fun host(): AssistantHost = hostFlow.value ?: AssistantHost(
        origin = config.baseUrl.trimEnd('/'),
        script = { context.assets.open("assistant/runtime.js").bufferedReader().use { it.readText() } },
        http = http,
        auth = object : AssistantHost.Auth {
            override suspend fun token(): String? = session.accessToken ?: refresher.get().accessTokenForRequest()
            override suspend fun refresh(stale: String?): String? =
                (refresher.get().refreshAfterUnauthenticated(stale) as? RefreshOutcome.Refreshed)?.accessToken
        },
    ).also { hostFlow.value = it }

    val running: Boolean get() = turn?.isActive == true

    /** 「在服务器上运行」开关(新对话沿用上一次的) */
    private var onServer = false

    init {
        // 服务器上跑的一轮推来的事件(doc/34):只认正开着的这个对话
        scope.launch {
            changes.changes.collect { c ->
                val conv = state.value.conversation?.id
                if (c.topic == Change.ASSISTANT && c.conversation != null && c.conversation == conv) onServerEvent(c.event)
            }
        }
    }

    fun send(args: SendArgs) {
        if (running) return
        if (onServer) {
            scope.launch { sendOnServer(args) }
            return
        }
        turn = scope.launch { host().send(args) }
    }

    fun newChat() {
        if (running) return
        scope.launch {
            host().reset()
            host().update { it.copy(onServer = onServer) }
        }
    }

    /** 打开一个旧对话;[server] 为 null 时问一下它在哪跑(通知点进来) */
    fun open(id: String, server: Boolean? = null) {
        if (running) return
        scope.launch {
            val h = host()
            h.open(id)
            onServer = server ?: runCatching { repo.runner(id) == "SERVER" }.getOrDefault(false)
            h.update { it.copy(onServer = onServer) }
            if (onServer) {
                runCatching { repo.currentRun(id) }.getOrNull()?.takeIf { it.status in ACTIVE }?.let(::applyRun)
            }
        }
    }

    /** 「在服务器上运行」开关;有对话时记到对话上,切回本机先把服务器写过的记录读回来 */
    fun setOnServer(on: Boolean) {
        val s = state.value
        if (running || (s.onServer && s.running)) return
        onServer = on
        scope.launch {
            val conv = state.value.conversation
            if (conv != null) {
                runCatching { repo.setRunner(conv.id, on) }.onFailure { e -> host().update { it.copy(error = e.message) } }
                if (!on) host().open(conv.id)
            }
            host().update { it.copy(onServer = on) }
        }
    }

    fun stop() {
        val s = state.value
        if (s.onServer && s.runId != null) {
            scope.launch { runCatching { repo.stopRun(s.runId) } }
            return
        }
        hostFlow.value?.stop()
    }

    fun answer(answer: String) {
        val s = state.value
        if (s.onServer && s.runId != null) {
            host().update { it.copy(approval = null) }
            scope.launch { runCatching { repo.answerRun(s.runId, answer) }.onFailure { e -> host().update { it.copy(error = e.message) } } }
            return
        }
        hostFlow.value?.answer(answer)
    }

    // ---- 服务器上跑(doc/34) ----

    private suspend fun sendOnServer(args: SendArgs) {
        val h = host()
        h.update { it.copy(error = null, running = true, items = it.items + AssistantItem("user", "pending-${it.items.size}", args.text.trim())) }
        try {
            val conv = state.value.conversation
            val run = repo.startRun(conv?.id, args.text, args.context, args.provider.id, args.model)
            if (conv == null) {
                h.update { it.copy(conversation = AssistantConversation(id = run.conversationId, title = args.text.trim().take(40))) }
            }
            applyRun(run)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            h.update { it.copy(running = false, error = e.message) }
        }
    }

    private fun onServerEvent(event: JsonElement?) {
        val obj = event as? JsonObject ?: return
        when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "run" -> {
                val run = obj["run"] as? JsonObject ?: return
                applyRun(
                    AiRun(
                        id = (run["id"] as? JsonPrimitive)?.contentOrNull ?: return,
                        conversationId = state.value.conversation?.id.orEmpty(),
                        status = (run["status"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        approval = run["approval"]?.takeIf { it !is JsonNull }?.toString(),
                        error = (run["error"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    ),
                )
            }
            else -> hostFlow.value?.applyServerEvent(obj.toString())
        }
    }

    private fun applyRun(run: AiRun) {
        val active = run.status in ACTIVE
        val approval = if (run.status == "waiting") run.approval?.let { raw ->
            runCatching { AssistantHost.json.decodeFromString(AssistantApproval.serializer(), raw) }.getOrNull()
        } else {
            null
        }
        host().update {
            it.copy(
                runId = run.id,
                running = active,
                approval = approval,
                live = if (active) it.live else "",
                error = if (run.status == "failed" && run.error.isNotEmpty()) run.error else it.error,
            )
        }
    }

    private companion object {
        val ACTIVE = setOf("queued", "running", "waiting")
    }

    fun close() {
        turn?.cancel()
        hostFlow.value?.close()
        hostFlow.value = null
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistantModule {
    @Binds
    abstract fun bindAssistantRepository(impl: ApolloAssistantRepository): AssistantRepository

    companion object {
        /** 登出:上一个账号的对话(连同 QuickJS)一并丢掉 */
        @Provides
        @IntoSet
        fun provideAssistantSignOutHook(engine: AssistantEngine): SignOutHook = SignOutHook { engine.close() }
    }
}
