package xyz.yophon.gopan.feature.quick

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.network.Change
import xyz.yophon.gopan.core.network.ChangeFeed
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.media.MediaRepository
import xyz.yophon.gopan.core.data.media.RecentPlay
import xyz.yophon.gopan.core.data.quick.QuickItem
import xyz.yophon.gopan.core.data.quick.QuickRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.transfer.TransferActions
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.NodeActionsViewModel
import xyz.yophon.gopan.feature.drive.NodeEffect
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.preview.PreviewSessionStore
import javax.inject.Inject

@Immutable
data class QuickUiState(
    val recent: Boolean,
    val status: ListStatus = ListStatus.Loading,
    val items: List<QuickItem> = emptyList(),
    val isRefreshing: Boolean = false,
    /** 相对时间(「3 小时前」)的基准,每次加载时更新 */
    val nowMillis: Long = System.currentTimeMillis(),
    val confirmClear: Boolean = false,
    /** 「最近」顶上的「接着看」:没看完的音视频与 PDF(doc/31 五) */
    val plays: List<RecentPlay> = emptyList(),
    val filter: RecentFilter = RecentFilter.ALL,
) {
    /** 按类型筛过的列表(收藏不筛) */
    val shown: List<QuickItem> get() = if (recent && filter != RecentFilter.ALL) items.filter { filter.matches(it.node.preview.kind) } else items
}

/**
 * 「收藏」与「最近」(doc/24)共用:收藏是常去的文件和文件夹,最近是打开过的文件(带看到哪,顶上一排「接着看」,doc/31 五)。
 * 操作(下载、分享、收藏、历史版本、详情)与文件页共用基类;回到前台时静默刷新(在别处收藏 / 打开过)。
 */
@HiltViewModel
class QuickViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repo: NodesRepository,
    transfers: TransferActions,
    previews: PreviewSessionStore,
    private val quick: QuickRepository,
    cache: ScreenCache,
    private val media: MediaRepository,
    changes: ChangeFeed = ChangeFeed.None,
) : NodeActionsViewModel(repo, transfers, previews) {

    private val _state = MutableStateFlow(QuickUiState(recent = savedStateHandle[RECENT_ARG] ?: false))
    val state: StateFlow<QuickUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var resumedOnce = false

    init {
        // 预取过的(启动时、别处改过之后)先画出来,再静默刷新;没有内存里的就读手机上存的上次那份
        val key = if (_state.value.recent) QuickRepository.RECENT_KEY else QuickRepository.STARRED_KEY
        val cached = cache[key]
        if (cached != null) {
            showCached(cached)
            load(LoadMode.SILENT)
        } else {
            viewModelScope.launch {
                val stored = cache.load(key)
                if (stored != null && _state.value.status == ListStatus.Loading) {
                    showCached(stored)
                    load(LoadMode.SILENT)
                } else {
                    load(LoadMode.INITIAL)
                }
            }
        }
        // 乐观修改(从最近里拿掉、清空)也跟着写回快照
        cache.mirror(viewModelScope, key, _state) { s -> s.items.takeIf { s.status == ListStatus.Content } }
        // 别的设备打开过文件、文件改名删除了:静默刷新(doc/32 二)
        viewModelScope.launch {
            changes.changes
                .filter { if (_state.value.recent) it.touchesRecent else it.topic == Change.FILES || it.topic == Change.RESYNC }
                .collect { if (_state.value.status == ListStatus.Content && loadJob?.isActive != true) load(LoadMode.SILENT) }
        }
    }

    private fun showCached(items: List<QuickItem>) {
        _state.update { it.copy(items = items, status = ListStatus.Content) }
    }

    private enum class LoadMode { INITIAL, REFRESH, SILENT }

    fun retry() = load(LoadMode.INITIAL)

    fun refresh() = load(LoadMode.REFRESH)

    /** 第一次 RESUME 是刚打开;之后是从预览 / 文件夹回来:顺序(最近)和星标可能都变了 */
    fun onResumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        if (_state.value.status == ListStatus.Content) load(LoadMode.SILENT)
    }

    private fun load(mode: LoadMode) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(status = if (mode == LoadMode.INITIAL) ListStatus.Loading else it.status, isRefreshing = mode == LoadMode.REFRESH)
            }
            if (_state.value.recent) launch { loadPlays() }
            gopanResult { if (_state.value.recent) quick.recent() else quick.starred() }
                .onSuccess { items ->
                    _state.update { it.copy(items = items, status = ListStatus.Content, isRefreshing = false, nowMillis = System.currentTimeMillis()) }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    val hadItems = _state.value.items.isNotEmpty()
                    _state.update {
                        if (mode == LoadMode.INITIAL || !hadItems) it.copy(status = ListStatus.Error(message), isRefreshing = false) else it.copy(isRefreshing = false)
                    }
                    if (mode == LoadMode.REFRESH && hadItems) send(NodeEffect.Message(UiText.Plain(message)))
                }
        }
    }

    /** 「接着看」取不到就不显示那一排,不打扰列表 */
    private suspend fun loadPlays() {
        gopanResult { media.recentPlays(PLAYS_LIMIT) }.onSuccess { plays -> _state.update { it.copy(plays = plays) } }
    }

    fun setFilter(filter: RecentFilter) = _state.update { it.copy(filter = filter) }

    /** 接着看:预览会从上次的位置继续 */
    fun resume(play: RecentPlay) = preview(play.node)

    /** 不再接着看:清掉进度,列表那一行也不再写看到哪;失败了放回来 */
    fun forgetProgress(play: RecentPlay) {
        val before = _state.value
        _state.update { s ->
            s.copy(
                plays = s.plays.filterNot { it.node.id == play.node.id },
                items = s.items.map { if (it.node.id == play.node.id) it.copy(progress = null) else it },
            )
        }
        viewModelScope.launch {
            gopanResult { media.clearProgress(play.node.id) }
                .onFailure { e ->
                    _state.update { it.copy(plays = before.plays, items = before.items) }
                    send(NodeEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }

    /** 文件夹进去;文件预览,左右翻的是列表里的文件 */
    fun onItemClick(node: Node) {
        if (node.isFolder) send(NodeEffect.OpenFolder(node.id)) else preview(node)
    }

    override fun previewSiblings(): List<Node> = _state.value.shown.map { it.node }.filterNot { it.isFolder }

    /** 从「最近」里拿掉一个(服务端连进度一起清):先从列表去掉,失败了再放回来 */
    fun forget(node: Node) {
        val before = _state.value.items
        val beforePlays = _state.value.plays
        _state.update { s -> s.copy(items = s.items.filterNot { it.node.id == node.id }, plays = s.plays.filterNot { it.node.id == node.id }) }
        viewModelScope.launch {
            gopanResult { quick.clearRecent(listOf(node.id)) }
                .onSuccess { send(NodeEffect.Message(UiText.Res(R.string.quick_msg_forgotten))) }
                .onFailure { e ->
                    _state.update { it.copy(items = before, plays = beforePlays) }
                    send(NodeEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }

    fun askClear() = _state.update { it.copy(confirmClear = true) }

    fun cancelClear() = _state.update { it.copy(confirmClear = false) }

    fun clearAll() {
        _state.update { it.copy(confirmClear = false) }
        viewModelScope.launch {
            gopanResult { quick.clearRecent(null) }
                .onSuccess { _state.update { it.copy(items = emptyList(), plays = emptyList()) } }
                .onFailure { e -> send(NodeEffect.Message(UiText.Plain(e.message ?: "操作失败"))) }
        }
    }

    override suspend fun afterMutation() {
        load(LoadMode.SILENT)
    }

    companion object {
        /** 与 Routes.Quick 的属性名一致 */
        const val RECENT_ARG = "recent"

        /** 「接着看」最多几个 */
        const val PLAYS_LIMIT = 12
    }
}
