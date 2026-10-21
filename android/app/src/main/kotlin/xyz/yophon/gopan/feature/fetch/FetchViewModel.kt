package xyz.yophon.gopan.feature.fetch

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.data.chat.ChatRepository
import xyz.yophon.gopan.core.data.jobs.JobErrors
import xyz.yophon.gopan.core.data.jobs.JobsRepository
import xyz.yophon.gopan.core.data.jobs.MAX_FETCH_URLS
import xyz.yophon.gopan.core.data.jobs.parseUrls
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.transfers.TransfersTabRequest
import javax.inject.Inject

@Immutable
data class FetchUiState(
    /** 本站开了离线下载(没问到之前当作没开,入口不出现) */
    val enabled: Boolean = false,
    val dialogOpen: Boolean = false,
    val text: String = "",
    /** 从 [text] 里识别出的链接 */
    val urls: List<String> = emptyList(),
    val submitting: Boolean = false,
    /** 对话框里的提交失败原因 */
    val error: String? = null,
    /** 传输助手:已提交过、或被关掉提示条的那组链接(草稿不变时提示条不再出现) */
    val chatHandled: List<String> = emptyList(),
) {
    val tooMany: Boolean get() = urls.size > MAX_FETCH_URLS
    val canSubmit: Boolean get() = urls.isNotEmpty() && !tooMany && !submitting

    /** 传输助手提示条要不要出现:开了离线下载、草稿里有链接、这组链接没处理过 */
    fun chatOffer(draft: String): ChatFetchOffer? {
        if (!enabled) return null
        return chatFetchOffer(draft)?.takeIf { it.urls != chatHandled }
    }
}

/** 传输助手草稿里识别到的链接:[urls] 最多 [MAX_FETCH_URLS] 条,[total] 是一共识别到几条 */
@Immutable
data class ChatFetchOffer(val urls: List<String>, val total: Int) {
    val capped: Boolean get() = total > urls.size
}

/** 草稿里有 http(s) 链接时给出提示条的内容;没有返回 null */
fun chatFetchOffer(draft: String): ChatFetchOffer? {
    val all = parseUrls(draft)
    if (all.isEmpty()) return null
    return ChatFetchOffer(all.take(MAX_FETCH_URLS), all.size)
}

sealed interface FetchEffect {
    /** 已加入后台任务(存到当前文件夹):提示 +「查看」 */
    data class Queued(val count: Int) : FetchEffect

    /** 已提交到传输助手文件夹:下完会话里自动出现文件卡片 */
    data class QueuedToChat(val count: Int) : FetchEffect

    data class Error(val message: String) : FetchEffect
}

/**
 * 离线下载(doc/21 三):粘贴链接,服务器在后台下到指定文件夹,关掉 App 也接着下。
 * 文件页的「+」→「离线下载」对话框,以及传输助手草稿上方的「离线下载这个链接」提示条共用。
 */
@HiltViewModel
class FetchViewModel @Inject constructor(
    private val jobs: JobsRepository,
    private val chat: ChatRepository,
    private val tabRequest: TransfersTabRequest,
) : ViewModel() {

    private val _state = MutableStateFlow(FetchUiState())
    val state: StateFlow<FetchUiState> = _state.asStateFlow()

    private val _effects = Channel<FetchEffect>(Channel.BUFFERED)
    val effects: Flow<FetchEffect> = _effects.receiveAsFlow()

    /** 开关已经问到了(仓库里也缓存着,之后不再问) */
    private var enabledKnown = false
    private var enabledJob: Job? = null

    init {
        checkEnabled()
    }

    /**
     * 问一次本站开没开离线下载。问不到(断网)就当没开、入口不出现;
     * 界面在要用到时(打开「+」菜单、草稿里出现链接)再调一次补问。
     */
    fun checkEnabled() {
        if (enabledKnown || enabledJob?.isActive == true) return
        enabledJob = viewModelScope.launch {
            gopanResult { jobs.fetchEnabled() }.onSuccess { enabled ->
                enabledKnown = true
                _state.update { it.copy(enabled = enabled) }
            }
        }
    }

    // ---- 文件页对话框 ----

    fun openDialog() {
        if (!_state.value.enabled) return
        _state.update { it.copy(dialogOpen = true, text = "", urls = emptyList(), error = null) }
    }

    fun dismissDialog() {
        if (_state.value.submitting) return
        _state.update { it.copy(dialogOpen = false, error = null) }
    }

    fun setText(text: String) {
        _state.update { it.copy(text = text, urls = parseUrls(text), error = null) }
    }

    /** 存到 [parentId](当前文件夹;null = 根目录) */
    fun submit(parentId: String?) {
        val s = _state.value
        if (!s.dialogOpen || !s.canSubmit) return
        val urls = s.urls
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            gopanResult { jobs.fetchUrls(urls, parentId) }
                .onSuccess { created ->
                    _state.update { it.copy(submitting = false, dialogOpen = false, text = "", urls = emptyList()) }
                    _effects.trySend(FetchEffect.Queued(created.size))
                }
                .onFailure { e -> _state.update { it.copy(submitting = false, error = JobErrors.messageFor(e)) } }
        }
    }

    // ---- 传输助手提示条 ----

    /** 草稿里的链接存到传输助手文件夹(没有就建);草稿不动 */
    fun submitToChat(draft: String) {
        val offer = chatFetchOffer(draft) ?: return
        if (!_state.value.enabled || _state.value.submitting) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            // 与传输助手发文件一样:文件夹没建过(或被删了)就建
            val folder = gopanResult { chat.ensureFolder() }.getOrElse { e ->
                _state.update { it.copy(submitting = false) }
                _effects.trySend(FetchEffect.Error("无法创建传输助手文件夹:${JobErrors.messageFor(e)}"))
                return@launch
            }
            gopanResult { jobs.fetchUrls(offer.urls, folder) }
                .onSuccess { created ->
                    _state.update { it.copy(submitting = false, chatHandled = offer.urls) }
                    _effects.trySend(FetchEffect.QueuedToChat(created.size))
                }
                .onFailure { e ->
                    _state.update { it.copy(submitting = false) }
                    _effects.trySend(FetchEffect.Error(JobErrors.messageFor(e)))
                }
        }
    }

    /** 不要提示条:这组链接不再提示(草稿里的链接变了才重新出现) */
    fun dismissChatOffer(draft: String) {
        val offer = chatFetchOffer(draft) ?: return
        _state.update { it.copy(chatHandled = offer.urls) }
    }

    /** 提交后点「查看」:传输列表直接停在「后台任务」栏 */
    fun showJobs() = tabRequest.requestJobs()
}
