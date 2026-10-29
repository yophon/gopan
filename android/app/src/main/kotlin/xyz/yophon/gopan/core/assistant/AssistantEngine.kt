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

    fun send(args: SendArgs) {
        if (running) return
        turn = scope.launch { host().send(args) }
    }

    fun newChat() {
        if (running) return
        scope.launch { host().reset() }
    }

    fun open(id: String) {
        if (running) return
        scope.launch { host().open(id) }
    }

    fun stop() = hostFlow.value?.stop()

    fun answer(answer: String) = hostFlow.value?.answer(answer)

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
