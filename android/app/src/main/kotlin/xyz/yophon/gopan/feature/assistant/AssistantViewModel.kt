package xyz.yophon.gopan.feature.assistant

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.assistant.AiConversationRow
import xyz.yophon.gopan.core.assistant.AiProvider
import xyz.yophon.gopan.core.assistant.AssistantEngine
import xyz.yophon.gopan.core.assistant.AssistantRepository
import xyz.yophon.gopan.core.assistant.SendArgs
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.core.ui.nav.Routes
import javax.inject.Inject

@Immutable
data class AssistantUi(
    /** null = 还在取 */
    val providers: List<AiProvider>? = null,
    val pick: String = "",
    val direct: Boolean = false,
    val history: List<AiConversationRow>? = null,
    val message: String? = null,
) {
    val current: Pair<AiProvider, String>? get() = providers?.let { AssistantLogic.resolvePick(it, pick) }
}

/**
 * 助手页面(doc/33 八之 2)。对话本身在 [AssistantEngine] 里(整个 App 一份),这里管选哪个服务 / 模型、
 * 对话记录列表,以及从文件页带进来的「你在看什么」。
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: AssistantEngine,
    private val repo: AssistantRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Routes.Assistant>()
    val contextLabel: String? = route.label
    private val context: String = route.context.orEmpty()

    val chat = engine.state

    private val _ui = MutableStateFlow(AssistantUi())
    val ui: StateFlow<AssistantUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(prefs.assistantPick, prefs.assistantDirect) { pick, direct -> pick to direct }.collect { (pick, direct) ->
                _ui.update { it.copy(pick = pick, direct = direct) }
            }
        }
        refreshProviders()
    }

    fun refreshProviders() {
        viewModelScope.launch {
            runCatching { repo.providers() }
                .onSuccess { list -> _ui.update { it.copy(providers = list) } }
                .onFailure { e -> _ui.update { it.copy(providers = it.providers ?: emptyList(), message = e.userText()) } }
        }
    }

    fun pick(provider: AiProvider, model: String) {
        viewModelScope.launch { prefs.setAssistantPick(AssistantLogic.pickValue(provider, model)) }
    }

    fun send(text: String) {
        val (provider, model) = _ui.value.current ?: return
        engine.send(
            SendArgs(
                text = text,
                context = context,
                provider = SendArgs.Provider(provider.id, provider.name, provider.kind, provider.baseUrl, provider.site),
                model = model,
                mode = if (_ui.value.direct && !provider.site) "direct" else "proxy",
            ),
        )
    }

    fun stop() = engine.stop()

    fun answer(answer: String) = engine.answer(answer)

    fun newChat() = engine.newChat()

    fun loadHistory() {
        viewModelScope.launch {
            runCatching { repo.conversations() }
                .onSuccess { list -> _ui.update { it.copy(history = list) } }
                .onFailure { e -> _ui.update { it.copy(history = it.history ?: emptyList(), message = e.userText()) } }
        }
    }

    fun open(row: AiConversationRow) {
        engine.open(row.id)
        val providers = _ui.value.providers.orEmpty()
        val p = providers.firstOrNull { it.id == row.providerId }
        if (p != null && row.model in p.models) pick(p, row.model)
    }

    fun delete(row: AiConversationRow) {
        viewModelScope.launch {
            runCatching { repo.deleteConversation(row.id) }
                .onSuccess {
                    if (chat.value.conversation?.id == row.id) engine.newChat()
                    _ui.update { u -> u.copy(history = u.history?.filterNot { it.id == row.id }) }
                }
                .onFailure { e -> _ui.update { it.copy(message = e.userText()) } }
        }
    }

    fun messageShown() = _ui.update { it.copy(message = null) }
}

/** 给用户看的出错原因 */
internal fun Throwable.userText(): String = (this as? GopanException ?: toGopanException()).message ?: "出错了"
