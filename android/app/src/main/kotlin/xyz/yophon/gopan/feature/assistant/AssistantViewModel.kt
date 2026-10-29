package xyz.yophon.gopan.feature.assistant

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.yophon.gopan.core.assistant.AiConversationRow
import xyz.yophon.gopan.core.assistant.AiProvider
import xyz.yophon.gopan.core.assistant.AssistantEngine
import xyz.yophon.gopan.core.assistant.AssistantRepository
import xyz.yophon.gopan.core.assistant.ChatImages
import xyz.yophon.gopan.core.assistant.RefNode
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
    /** 服务器上能不能跑(装了运行环境,doc/34) */
    val serverAvailable: Boolean = false,
    /** 要随下一句发的图(JPEG data URL,已缩好,doc/35) */
    val pendingImages: List<String> = emptyList(),
    /** 回答里引用的文件,按 id;null = 查不到(删了、没权限),不在表里 = 还没查 */
    val refs: Map<String, RefNode?> = emptyMap(),
) {
    val current: Pair<AiProvider, String>? get() = providers?.let { AssistantLogic.resolvePick(it, pick) }
}

/**
 * 助手页面(doc/33 八之 2)。对话本身在 [AssistantEngine] 里(整个 App 一份),这里管选哪个服务 / 模型、
 * 对话记录列表,以及从文件页带进来的「你在看什么」。
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val engine: AssistantEngine,
    private val repo: AssistantRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Routes.Assistant>()
    private val context: String = route.context.orEmpty()

    /** 从文件页带进来时选中了几项(「这几个」指它们);在哪个文件夹不用提,模型那边知道 */
    val selectionCount: Int = Regex("""选中了 (\d+) 项""").find(context)?.groupValues?.get(1)?.toIntOrNull() ?: 0

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
        viewModelScope.launch {
            runCatching { repo.runAvailable() }.onSuccess { a -> _ui.update { it.copy(serverAvailable = a) } }
        }
        route.conversation?.let { engine.open(it) }
        // 回答里引用的文件:出现新的 id 就查(doc/35 六)
        viewModelScope.launch {
            chat.map { s -> (s.items.filter { it.kind == "assistant" }.map { it.text } + s.live).flatMap { AssistantLogic.refsIn(it) }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect(::ensureRefs)
        }
    }

    // 缩略图地址是预签名的,15 分钟过期:10 分钟后再用到重新查
    private val refFetchedAt = mutableMapOf<String, Long>()

    /** [keys] 是引用里写的 id 或路径;查到的按同一个键记下 */
    private fun ensureRefs(keys: Set<String>) {
        val now = System.currentTimeMillis()
        val todo = keys.filter { k -> refFetchedAt[k]?.let { now - it > 10 * 60_000 } ?: true }.take(100)
        if (todo.isEmpty()) return
        todo.forEach { refFetchedAt[it] = now }
        val (paths, ids) = todo.partition { it.startsWith("/") }
        viewModelScope.launch {
            runCatching {
                val byId = if (ids.isEmpty()) emptyList() else repo.nodesByIds(ids)
                val byPath = if (paths.isEmpty()) emptyList() else repo.nodesByPaths(paths)
                byId.associateBy { it.id } + byPath.filter { it.path != null }.associateBy { it.path!! }
            }
                .onSuccess { found -> _ui.update { u -> u.copy(refs = u.refs + todo.associateWith { found[it] }) } }
                .onFailure { todo.forEach(refFetchedAt::remove) }
        }
    }

    /** 选了图:缩好放进待发(最多 [ChatImages.MAX] 张) */
    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            val room = ChatImages.MAX - _ui.value.pendingImages.size
            val urls = withContext(Dispatchers.Default) { uris.take(room.coerceAtLeast(0)).mapNotNull { ChatImages.load(appContext.contentResolver, it) } }
            _ui.update { it.copy(pendingImages = (it.pendingImages + urls).take(ChatImages.MAX)) }
            if (urls.size < uris.size) _ui.update { it.copy(message = appContext.getString(xyz.yophon.gopan.R.string.assistant_images_skipped)) }
        }
    }

    fun removeImage(index: Int) = _ui.update { u -> u.copy(pendingImages = u.pendingImages.filterIndexed { i, _ -> i != index }) }

    fun setOnServer(on: Boolean) = engine.setOnServer(on)

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

    /** 发出去了返回 true(图太大时不发,输入框里的字留着) */
    fun send(text: String): Boolean {
        val (provider, model) = _ui.value.current ?: return false
        val images = ChatImages.fitBudget(_ui.value.pendingImages)
        if (images == null) {
            _ui.update { it.copy(message = appContext.getString(xyz.yophon.gopan.R.string.assistant_images_too_large)) }
            return false
        }
        _ui.update { it.copy(pendingImages = emptyList()) }
        engine.send(
            SendArgs(
                text = text,
                context = context,
                images = images,
                provider = SendArgs.Provider(provider.id, provider.name, provider.kind, provider.baseUrl, provider.site),
                model = model,
                mode = if (_ui.value.direct && !provider.site) "direct" else "proxy",
            ),
        )
        return true
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
        engine.open(row.id, server = row.runner == "SERVER")
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
