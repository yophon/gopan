package xyz.yophon.gopan.core.network

import androidx.compose.runtime.Immutable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.NodesRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 服务端推来的一条「哪儿变了」(doc/32 二) */
@Immutable
@Serializable
data class Change(
    @SerialName("t") val topic: String,
    /** files:变了的文件夹,"" = 根目录 */
    @SerialName("f") val folders: List<String>? = null,
    /** files:太多了没列 */
    val all: Boolean = false,
    /** files:回收站也变了 */
    val trash: Boolean = false,
) {
    /** 正开着的这个文件夹([folderId] 为 null = 根目录)要不要刷新 */
    fun touchesFolder(folderId: String?): Boolean =
        topic == RESYNC || (topic == FILES && (all || folders.orEmpty().contains(folderId.orEmpty())))

    val touchesTrash: Boolean get() = topic == RESYNC || (topic == FILES && trash)

    /** 「最近」:打开记录变了,或者文件改名、删除了 */
    val touchesRecent: Boolean get() = topic == RESYNC || topic == RECENT || topic == FILES

    val touchesChat: Boolean get() = topic == RESYNC || topic == CHAT

    companion object {
        const val FILES = "files"
        const val CHAT = "chat"
        const val RECENT = "recent"
        const val RESYNC = "resync"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(data: String): Change? = runCatching { json.decodeFromString<Change>(data) }.getOrNull()
    }
}

/** 列表页订阅的改动来源;测试与没登录时用 [None] */
interface ChangeFeed {
    val changes: Flow<Change>

    /** 连着时传输助手把轮询放慢 */
    val connected: StateFlow<Boolean>

    object None : ChangeFeed {
        override val changes: Flow<Change> = emptyFlow()
        override val connected: StateFlow<Boolean> = MutableStateFlow(false)
    }
}

/**
 * 自动刷新的推送连接(doc/32 二):App 在前台时连着 GET /api/events(Server-Sent Events),
 * 别处改了网盘就告诉正开着的列表页刷新;回到后台就断开,回来时各页面本来就会刷新一次。
 * 服务端每 5 分钟断一次让客户端带新凭据重连;出错退避重连(2 秒起,最长 1 分钟)。
 */
@Singleton
class ChangeStream @Inject constructor(
    base: OkHttpClient,
    config: AppConfig,
    private val session: SessionStore,
    private val refresher: dagger.Lazy<TokenRefresher>,
    private val nodes: NodesRepository,
) : ChangeFeed {

    // 服务端 25 秒一次心跳;读超时放宽到 60 秒,只有真断了才超时
    private val client = base.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private val url = config.baseUrl.trimEnd('/') + "/api/events"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: Flow<Change> = _changes.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** App 回到前台 */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    /** App 进了后台 */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    private enum class End { CLOSED, UNAUTHORIZED }

    private suspend fun loop() {
        var backoff = MIN_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val token = session.accessToken ?: refresher.get().accessTokenForRequest()
            if (token == null) { // 没登录:过一会儿再看
                delay(IDLE_MS)
                continue
            }
            val end = try {
                stream(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                _connected.value = false
            }
            when (end) {
                End.CLOSED -> backoff = MIN_BACKOFF_MS
                End.UNAUTHORIZED -> when (refresher.get().refreshAfterUnauthenticated(token)) {
                    is RefreshOutcome.Refreshed -> Unit
                    RefreshOutcome.SignedOut -> delay(IDLE_MS)
                    RefreshOutcome.Unavailable -> delay(backoff)
                }
                null -> {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private suspend fun stream(token: String): End {
        val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        // 读流是阻塞的:协程取消时直接掐掉请求
        val cancel = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { res ->
                if (res.code == 401) return End.UNAUTHORIZED
                if (!res.isSuccessful) throw IOException("events ${res.code}")
                val source = res.body?.source() ?: throw IOException("events: empty body")
                _connected.value = true
                val data = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: return End.CLOSED
                    when {
                        line.isEmpty() -> if (data.isNotEmpty()) {
                            Change.parse(data.toString())?.let(::deliver)
                            data.clear()
                        }
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                    }
                }
            }
        } finally {
            cancel.dispose()
        }
    }

    private fun deliver(change: Change) {
        // 没开着的列表页回到前台时按仓库变更计数刷新
        if (change.topic == Change.FILES || change.topic == Change.RESYNC) nodes.markChanged()
        _changes.tryEmit(change)
    }

    private companion object {
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val IDLE_MS = 30_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChangeFeedModule {
    @Binds
    abstract fun bindChangeFeed(impl: ChangeStream): ChangeFeed
}
