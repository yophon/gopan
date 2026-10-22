package xyz.yophon.gopan.feature.feeds

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
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.CachedPage
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.feeds.Feed
import xyz.yophon.gopan.core.data.feeds.FeedItem
import xyz.yophon.gopan.core.data.feeds.FeedItemState
import xyz.yophon.gopan.core.data.feeds.FeedKind
import xyz.yophon.gopan.core.data.feeds.FeedMode
import xyz.yophon.gopan.core.data.feeds.FeedPreview
import xyz.yophon.gopan.core.data.feeds.FeedsRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject

// ---- 列表 ----

@Immutable
data class FeedsUiState(
    val status: ListStatus = ListStatus.Loading,
    val feeds: List<Feed> = emptyList(),
    val refreshing: Boolean = false,
)

/** 「我的 → 订阅」:全部订阅(服务端按创建先后排好,每人最多 100 个,不分页) */
@HiltViewModel
class FeedsViewModel @Inject constructor(
    private val repo: FeedsRepository,
    cache: ScreenCache,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedsUiState())
    val state: StateFlow<FeedsUiState> = _state.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = _messages.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        // 上次的列表先画出来,再静默刷新
        val cached = cache[CACHE_KEY]
        if (cached != null) _state.update { it.copy(status = ListStatus.Content, feeds = cached) }
        cache.mirror(viewModelScope, CACHE_KEY, _state) { s -> s.feeds.takeIf { s.status == ListStatus.Content } }
        load(silent = cached != null)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    fun retry() = load(silent = false)

    /** 回到这一页(从详情回来、添加完):静默刷新 */
    fun onResumed() {
        if (loadedOnce && loadJob?.isActive != true) load(silent = true)
    }

    private fun load(silent: Boolean) {
        loadJob?.cancel()
        if (!silent) _state.update { it.copy(status = ListStatus.Loading) }
        loadJob = viewModelScope.launch {
            gopanResult { repo.feeds() }
                .onSuccess { feeds ->
                    loadedOnce = true
                    _state.update { it.copy(status = ListStatus.Content, feeds = feeds, refreshing = false) }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    if (_state.value.status == ListStatus.Content) {
                        _state.update { it.copy(refreshing = false) }
                        _messages.trySend(UiText.Plain(message))
                    } else {
                        _state.update { it.copy(status = ListStatus.Error(message), refreshing = false) }
                    }
                }
        }
    }

    companion object {
        val CACHE_KEY = ScreenKey<List<Feed>>("feeds")
    }
}

// ---- 添加 ----

@Immutable
data class FeedAddUiState(
    val url: String = "",
    /** GitHub:资源文件名通配 */
    val assetPattern: String = "",
    val previewing: Boolean = false,
    val preview: FeedPreview? = null,
    /** 预览对应的地址;地址改了要重新预览才能订阅 */
    val previewedUrl: String? = null,
    val mode: FeedMode = FeedMode.NONE,
    val creating: Boolean = false,
    val error: String? = null,
) {
    val previewStale: Boolean get() = preview != null && previewedUrl != url.trim()
    val canPreview: Boolean get() = url.isNotBlank() && !previewing && !creating
    val canCreate: Boolean get() = preview != null && !previewStale && !previewing && !creating
}

/** 添加订阅:贴地址 → 预览(标题、是不是播客、最近几条)→ 选方式 → 订阅。其余设置用服务端默认值 */
@HiltViewModel
class FeedAddViewModel @Inject constructor(private val repo: FeedsRepository) : ViewModel() {

    private val _state = MutableStateFlow(FeedAddUiState())
    val state: StateFlow<FeedAddUiState> = _state.asStateFlow()

    /** 订阅成功:进这个订阅的详情 */
    private val _created = Channel<Feed>(Channel.BUFFERED)
    val created: Flow<Feed> = _created.receiveAsFlow()

    private var previewJob: Job? = null

    fun setUrl(url: String) {
        _state.update { it.copy(url = url, error = null) }
    }

    fun setAssetPattern(pattern: String) {
        _state.update { it.copy(assetPattern = pattern) }
    }

    fun setMode(mode: FeedMode) {
        val preview = _state.value.preview ?: return
        if (mode !in FeedsLogic.modesFor(preview.kind)) return
        _state.update { it.copy(mode = mode) }
    }

    fun preview() {
        val s = _state.value
        if (!s.canPreview) return
        val url = s.url.trim()
        _state.update { it.copy(previewing = true, error = null) }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            gopanResult { repo.preview(url, s.assetPattern) }
                .onSuccess { p ->
                    _state.update {
                        // 同一个地址再预览(改了通配):保留已经选的方式
                        val keepMode = it.preview != null && it.previewedUrl == url && it.mode in FeedsLogic.modesFor(p.kind)
                        it.copy(
                            previewing = false,
                            preview = p,
                            previewedUrl = url,
                            mode = if (keepMode) it.mode else FeedsLogic.defaultMode(p),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(previewing = false, error = e.message ?: "读取失败") } }
        }
    }

    fun create() {
        val s = _state.value
        if (!s.canCreate) return
        val preview = s.preview ?: return
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            val pattern = s.assetPattern.takeIf { preview.kind == FeedKind.GITHUB }
            gopanResult { repo.create(s.previewedUrl ?: s.url.trim(), s.mode, pattern) }
                .onSuccess { feed ->
                    _state.update { it.copy(creating = false) }
                    _created.trySend(feed)
                }
                .onFailure { e -> _state.update { it.copy(creating = false, error = e.message ?: "订阅失败") } }
        }
    }
}

// ---- 详情 ----

@Immutable
data class FeedDetailUiState(
    val feed: Feed? = null,
    val status: ListStatus = ListStatus.Loading,
    val items: List<FeedItem> = emptyList(),
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    /** 立即检查进行中(服务端同步检查,最多 20 秒) */
    val checking: Boolean = false,
    /** 暂停 / 恢复 / 退订进行中 */
    val busy: Boolean = false,
    /** 正在提交下载 / 剪藏的条目 */
    val submitting: Set<String> = emptySet(),
)

/** 详情页的快照:订阅信息 + 已加载的条目 */
data class FeedDetailSnapshot(val feed: Feed, val page: CachedPage<FeedItem>)

sealed interface FeedDetailEffect {
    data class Message(val text: UiText) : FeedDetailEffect

    /** 退订了:回列表 */
    data object Closed : FeedDetailEffect
}

/**
 * 一个订阅:基本信息与条目(分页,新的在前);立即检查、暂停 / 恢复、退订;单条下载 / 剪藏。
 * 有处理中的条目时,页面可见期间每 3 秒刷新第一页,直到它们结束。
 */
@HiltViewModel
class FeedDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: FeedsRepository,
    private val nodes: NodesRepository,
    private val cache: ScreenCache,
) : ViewModel() {

    private val feedId: String = requireNotNull(savedStateHandle.get<String>("feedId")) { "缺少 feedId" }
    private val cacheKey = ScreenKey<FeedDetailSnapshot>("feed/$feedId")

    private val _state = MutableStateFlow(FeedDetailUiState())
    val state: StateFlow<FeedDetailUiState> = _state.asStateFlow()

    private val _effects = Channel<FeedDetailEffect>(Channel.BUFFERED)
    val effects: Flow<FeedDetailEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var pollJob: Job? = null
    private var visible = false
    private var loadedOnce = false

    init {
        // 看过的订阅先画上次的信息与条目,再静默刷新
        val cached = cache[cacheKey]
        if (cached != null) {
            _state.update {
                it.copy(feed = cached.feed, status = ListStatus.Content, items = cached.page.items, nextCursor = cached.page.nextCursor)
            }
        }
        cache.mirror(viewModelScope, cacheKey, _state) { s ->
            val feed = s.feed
            if (s.status == ListStatus.Content && feed != null) FeedDetailSnapshot(feed, CachedPage(s.items, s.nextCursor)) else null
        }
        load(silent = cached != null)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    fun retry() = load(silent = false)

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            pollJob?.cancel()
            return
        }
        // 回到这一页:静默刷新一次(期间可能自动检查过),有处理中的接着轮询
        if (loadedOnce && loadJob?.isActive != true) load(silent = true) else maybePoll()
    }

    /** 订阅信息与条目第一页一起拉;进行中的翻页作废 */
    private fun load(silent: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        if (!silent) _state.update { it.copy(status = ListStatus.Loading) }
        _state.update { it.copy(loadingMore = false, loadMoreError = null) }
        loadJob = viewModelScope.launch {
            val feed = gopanResult { repo.feed(feedId) }
            val page = gopanResult { repo.items(feedId, null) }
            val error = feed.exceptionOrNull() ?: page.exceptionOrNull()
            if (error == null) {
                loadedOnce = true
                val p = page.getOrThrow()
                _state.update {
                    it.copy(feed = feed.getOrThrow(), status = ListStatus.Content, items = p.items, nextCursor = p.nextCursor, refreshing = false)
                }
                maybePoll()
            } else {
                val message = error.message ?: "加载失败"
                if (_state.value.status == ListStatus.Content) {
                    _state.update { it.copy(refreshing = false) }
                    send(UiText.Plain(message))
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
        moreJob = viewModelScope.launch {
            gopanResult { repo.items(feedId, cursor) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(items = (it.items + page.items).distinctBy { i -> i.id }, nextCursor = page.nextCursor, loadingMore = false)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, loadMoreError = e.message ?: "加载失败") } }
        }
    }

    /** 有处理中的条目且页面可见:每 [POLL_MS] 拉一次第一页并进列表,全部结束就停 */
    private fun maybePoll() {
        if (!visible || pollJob?.isActive == true || !FeedsLogic.hasQueued(_state.value.items)) return
        pollJob = viewModelScope.launch {
            while (FeedsLogic.hasQueued(_state.value.items)) {
                delay(POLL_MS)
                val before = _state.value.items.filter { it.state == FeedItemState.QUEUED }.map { it.id }.toSet()
                gopanResult { repo.items(feedId, null) }.onSuccess { page ->
                    _state.update { it.copy(items = FeedsLogic.merge(it.items, page.items)) }
                    // 刚下好的文件:文件列表回到前台时刷新
                    if (_state.value.items.any { it.id in before && it.state != FeedItemState.QUEUED }) nodes.markChanged()
                }
            }
            // 新内容数可能变了
            gopanResult { repo.feed(feedId) }.onSuccess { f -> _state.update { it.copy(feed = f) } }
        }
    }

    /** 立即检查:服务端同步检查完再回(最多 20 秒);之后重拉条目 */
    fun checkNow() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            gopanResult { repo.refresh(feedId) }
                .onSuccess { feed ->
                    _state.update { it.copy(checking = false, feed = feed) }
                    feed.lastError?.let { send(UiText.Res(R.string.feeds_error, it)) } ?: send(UiText.Res(R.string.feeds_msg_checked))
                    load(silent = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(checking = false) }
                    send(UiText.Plain(e.message ?: "检查失败"))
                }
        }
    }

    /** 暂停 / 恢复(其余设置原样带上) */
    fun toggleEnabled() {
        val feed = _state.value.feed ?: return
        if (_state.value.busy) return
        val enable = !feed.enabled
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gopanResult { repo.setEnabled(feed, enable) }
                .onSuccess { updated ->
                    _state.update { it.copy(busy = false, feed = updated) }
                    send(UiText.Res(if (enable) R.string.feeds_msg_resumed else R.string.feeds_msg_paused))
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    send(UiText.Plain(e.message ?: "操作失败"))
                }
        }
    }

    /** 退订:已下好的文件不动 */
    fun unsubscribe() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gopanResult { repo.delete(feedId) }
                .onSuccess {
                    cache.remove(cacheKey)
                    _state.update { it.copy(busy = false) }
                    send(UiText.Res(R.string.feeds_msg_unsubscribed))
                    _effects.trySend(FeedDetailEffect.Closed)
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    send(UiText.Plain(e.message ?: "退订失败"))
                }
        }
    }

    /** 单条下载附件 / 剪藏正文:条目立即变成「处理中」并开始轮询 */
    fun fetch(item: FeedItem, mode: FeedMode) {
        if (item.id in _state.value.submitting) return
        _state.update { it.copy(submitting = it.submitting + item.id) }
        viewModelScope.launch {
            gopanResult { repo.fetchItem(item.id, mode) }
                .onSuccess { updated ->
                    _state.update { s ->
                        s.copy(submitting = s.submitting - item.id, items = s.items.map { if (it.id == updated.id) updated else it })
                    }
                    send(UiText.Res(R.string.feeds_msg_queued))
                    maybePoll()
                }
                .onFailure { e ->
                    _state.update { it.copy(submitting = it.submitting - item.id) }
                    send(UiText.Plain(e.message ?: "操作失败"))
                }
        }
    }

    private fun send(text: UiText) {
        _effects.trySend(FeedDetailEffect.Message(text))
    }

    companion object {
        const val POLL_MS = 3_000L
    }
}
