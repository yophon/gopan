package xyz.yophon.gopan.feature.clips

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.CachedPage
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.clips.Clip
import xyz.yophon.gopan.core.data.clips.ClipsRepository
import xyz.yophon.gopan.core.data.markdown.MarkdownUrls
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject
import javax.inject.Singleton

// ---- 列表 ----

@Immutable
data class ClipsUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<Clip> = emptyList(),
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    /** 只看未读 */
    val unreadOnly: Boolean = false,
)

/** 「我的 → 剪藏」:新的在前,分页;全部 / 未读 */
@HiltViewModel
class ClipsViewModel @Inject constructor(
    private val repo: ClipsRepository,
    private val cache: ScreenCache,
) : ViewModel() {

    private val _state = MutableStateFlow(ClipsUiState())
    val state: StateFlow<ClipsUiState> = _state.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = _messages.receiveAsFlow()

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var loadedOnce = false

    init {
        // 「全部」「未读」各存一份,哪个看过就先画哪个
        cache.mirrorKeyed(viewModelScope, _state) { s ->
            if (s.status == ListStatus.Content) cacheKey(s.unreadOnly) to CachedPage(s.items, s.nextCursor) else null
        }
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    fun retry() = load(silent = false)

    /** 从阅读页回来:已读状态变了、可能删了一篇,静默刷新 */
    fun onResumed() {
        if (loadedOnce && loadJob?.isActive != true) load(silent = true)
    }

    fun setUnreadOnly(value: Boolean) {
        if (_state.value.unreadOnly == value) return
        // 换筛选的同一步就离开 Content,免得空列表被当成新筛选的快照存起来
        _state.update { it.copy(unreadOnly = value, items = emptyList(), nextCursor = null, status = ListStatus.Loading) }
        load(silent = false)
    }

    /** 非静默加载时这个筛选有快照:先画快照,联网按静默刷新处理(失败保留快照、只提示) */
    private fun load(silent: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        val unreadOnly = _state.value.unreadOnly
        val cached = if (silent) null else cache[cacheKey(unreadOnly)]
        _state.update {
            if (cached != null) {
                it.copy(status = ListStatus.Content, items = cached.items, nextCursor = cached.nextCursor, loadingMore = false, loadMoreError = null)
            } else {
                it.copy(status = if (silent) it.status else ListStatus.Loading, loadingMore = false, loadMoreError = null)
            }
        }
        loadJob = viewModelScope.launch {
            gopanResult { repo.clips(unreadOnly, cursor = null) }
                .onSuccess { page ->
                    loadedOnce = true
                    _state.update { it.copy(status = ListStatus.Content, items = page.items, nextCursor = page.nextCursor, refreshing = false) }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    if (_state.value.status == ListStatus.Content && _state.value.items.isNotEmpty()) {
                        _state.update { it.copy(refreshing = false) }
                        _messages.trySend(UiText.Plain(message))
                    } else {
                        _state.update { it.copy(status = ListStatus.Error(message), refreshing = false) }
                    }
                }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.status != ListStatus.Content || s.loadingMore || loadJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        val unreadOnly = s.unreadOnly
        moreJob = viewModelScope.launch {
            gopanResult { repo.clips(unreadOnly, cursor) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(items = (it.items + page.items).distinctBy { c -> c.id }, nextCursor = page.nextCursor, loadingMore = false)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, loadMoreError = e.message ?: "加载失败") } }
        }
    }

    companion object {
        fun cacheKey(unreadOnly: Boolean) = ScreenKey<CachedPage<Clip>>("clips?unread=$unreadOnly")
    }
}

// ---- 阅读页 ----

@Immutable
data class ClipReaderUiState(
    /** 这篇剪藏;不是剪藏的 .md(或还没查到)为 null,只显示正文 */
    val clip: Clip? = null,
    /** 渲染页地址;还没拼好为 null */
    val url: String? = null,
    /** 拼地址失败(登录失效、断网) */
    val error: String? = null,
    /** 标已读 / 删除进行中 */
    val busy: Boolean = false,
)

sealed interface ClipReaderEffect {
    data class Message(val text: UiText) : ClipReaderEffect

    /** 删了:回列表 */
    data object Closed : ClipReaderEffect
}

/**
 * 剪藏的阅读页:WebView 显示 /md/{nodeId};顶栏「标记已读 / 未读」「打开原网页」「删除」(.md 移进回收站)。
 * 打开后停留 [ClipLogic.AUTO_READ_MS] 自动标为已读。
 */
@HiltViewModel
class ClipReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val clips: ClipsRepository,
    private val nodes: NodesRepository,
    private val urls: MarkdownUrls,
) : ViewModel() {

    val nodeId: String = requireNotNull(savedStateHandle.get<String>("nodeId")) { "缺少 nodeId" }
    private val clipId: String? = savedStateHandle.get<String>("clipId")

    private val _state = MutableStateFlow(ClipReaderUiState())
    val state: StateFlow<ClipReaderUiState> = _state.asStateFlow()

    private val _effects = Channel<ClipReaderEffect>(Channel.BUFFERED)
    val effects: Flow<ClipReaderEffect> = _effects.receiveAsFlow()

    val baseUrl: String get() = urls.baseUrl

    private var dark: Boolean? = null
    private var pageJob: Job? = null
    private var autoReadJob: Job? = null
    private var visible = false
    private var autoReadDone = false

    init {
        viewModelScope.launch {
            val result = gopanResult { if (clipId != null) clips.clip(clipId) else clips.nodeClip(nodeId) }
            result.onSuccess { clip ->
                _state.update { it.copy(clip = clip) }
                scheduleAutoRead()
            }.onFailure { Timber.w("查剪藏失败:%s", it.message) }
        }
    }

    /** 按当前深浅拼渲染页地址;深浅变了重拼 */
    fun load(dark: Boolean) {
        if (this.dark == dark && (_state.value.url != null || pageJob?.isActive == true)) return
        this.dark = dark
        buildUrl()
    }

    fun retry() = buildUrl()

    private fun buildUrl() {
        pageJob?.cancel()
        _state.update { it.copy(error = null) }
        pageJob = viewModelScope.launch {
            gopanResult { urls.page(nodeId, dark) }
                .onSuccess { url -> _state.update { it.copy(url = url) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "加载失败") } }
        }
    }

    /** WebView 里点到的站内 /md/ 链接:换新 token */
    suspend fun retoken(url: String): String = urls.retoken(url)

    fun setVisible(value: Boolean) {
        visible = value
        if (value) scheduleAutoRead() else autoReadJob?.cancel()
    }

    /** 未读的剪藏在页面显示着停留一会儿就标已读(只自动标一次;手动标回未读后不再自动标) */
    private fun scheduleAutoRead() {
        val clip = _state.value.clip ?: return
        if (!visible || autoReadDone || !clip.unread || autoReadJob?.isActive == true) return
        autoReadJob = viewModelScope.launch {
            delay(ClipLogic.AUTO_READ_MS)
            autoReadDone = true
            gopanResult { clips.markRead(clip.id, true) }
                .onSuccess { updated -> _state.update { it.copy(clip = updated) } }
                .onFailure { Timber.w("自动标已读失败:%s", it.message) }
        }
    }

    fun setRead(read: Boolean) {
        val clip = _state.value.clip ?: return
        if (_state.value.busy) return
        autoReadJob?.cancel()
        autoReadDone = true
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gopanResult { clips.markRead(clip.id, read) }
                .onSuccess { updated ->
                    _state.update { it.copy(busy = false, clip = updated) }
                    _effects.trySend(ClipReaderEffect.Message(UiText.Res(if (read) R.string.clips_msg_read else R.string.clips_msg_unread)))
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    _effects.trySend(ClipReaderEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }

    /** 删除:那篇 .md 和它的图片文件夹一起移进回收站 */
    fun delete() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        val ids = listOfNotNull(nodeId, _state.value.clip?.assetsId)
        viewModelScope.launch {
            gopanResult { nodes.delete(ids) }
                .onSuccess {
                    _state.update { it.copy(busy = false) }
                    _effects.trySend(ClipReaderEffect.Message(UiText.Res(R.string.clips_msg_deleted)))
                    _effects.trySend(ClipReaderEffect.Closed)
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    _effects.trySend(ClipReaderEffect.Message(UiText.Plain(e.message ?: "删除失败")))
                }
        }
    }
}

// ---- 预览里的 Markdown 页 ----

@Immutable
data class MarkdownPageUiState(
    val url: String? = null,
    val error: String? = null,
    /** 这篇是剪藏:顶上显示来源条 */
    val clip: Clip? = null,
    val marking: Boolean = false,
)

/**
 * 文件预览里渲染 .md 的那一页(每个文件一个,按节点 id 区分):拼渲染页地址、查是不是剪藏(是就显示来源条:
 * 站点 · 打开原网页 · 标记已读)。
 */
@HiltViewModel
class MarkdownPageViewModel @Inject constructor(
    private val urls: MarkdownUrls,
    private val clips: ClipsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MarkdownPageUiState())
    val state: StateFlow<MarkdownPageUiState> = _state.asStateFlow()

    val baseUrl: String get() = urls.baseUrl

    private var nodeId: String? = null
    private var dark: Boolean? = null
    private var pageJob: Job? = null

    fun bind(nodeId: String, dark: Boolean) {
        if (this.nodeId == nodeId && this.dark == dark) return
        val first = this.nodeId != nodeId
        this.nodeId = nodeId
        this.dark = dark
        buildUrl()
        if (first) {
            viewModelScope.launch {
                gopanResult { clips.nodeClip(nodeId) }
                    .onSuccess { clip -> _state.update { it.copy(clip = clip) } }
                    .onFailure { Timber.w("查剪藏失败:%s", it.message) }
            }
        }
    }

    fun retry() = buildUrl()

    private fun buildUrl() {
        val id = nodeId ?: return
        pageJob?.cancel()
        _state.update { it.copy(error = null) }
        pageJob = viewModelScope.launch {
            gopanResult { urls.page(id, dark) }
                .onSuccess { url -> _state.update { it.copy(url = url) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "加载失败") } }
        }
    }

    suspend fun retoken(url: String): String = urls.retoken(url)

    fun markRead() {
        val clip = _state.value.clip ?: return
        if (_state.value.marking || !clip.unread) return
        _state.update { it.copy(marking = true) }
        viewModelScope.launch {
            gopanResult { clips.markRead(clip.id, true) }
                .onSuccess { updated -> _state.update { it.copy(marking = false, clip = updated) } }
                .onFailure { _state.update { it.copy(marking = false) } }
        }
    }
}

// ---- 系统分享剪藏后「打开」:交给主界面进阅读页 ----

/** 待打开的剪藏(那篇 .md 的节点 id);主界面收集后跳到阅读页并清空 */
@Singleton
class ClipOpenRequests @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun post(nodeId: String) {
        _pending.value = nodeId
    }

    fun consume(nodeId: String) {
        _pending.compareAndSet(nodeId, null)
    }
}

@HiltViewModel
class ClipOpenViewModel @Inject constructor(private val requests: ClipOpenRequests) : ViewModel() {
    val pending: StateFlow<String?> = requests.pending

    fun consume(nodeId: String) = requests.consume(nodeId)
}
