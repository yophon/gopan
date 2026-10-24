package xyz.yophon.gopan.feature.quick

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
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
)

/**
 * 「收藏」与「最近」(doc/24)共用:收藏是常去的文件和文件夹,最近是打开过的文件。
 * 操作(下载、分享、收藏、历史版本、详情)与文件页共用基类;回到前台时静默刷新(在别处收藏 / 打开过)。
 */
@HiltViewModel
class QuickViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repo: NodesRepository,
    transfers: TransferActions,
    previews: PreviewSessionStore,
    private val quick: QuickRepository,
) : NodeActionsViewModel(repo, transfers, previews) {

    private val _state = MutableStateFlow(QuickUiState(recent = savedStateHandle[RECENT_ARG] ?: false))
    val state: StateFlow<QuickUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var resumedOnce = false

    init {
        load(LoadMode.INITIAL)
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

    /** 文件夹进去;文件预览,左右翻的是列表里的文件 */
    fun onItemClick(node: Node) {
        if (node.isFolder) send(NodeEffect.OpenFolder(node.id)) else preview(node)
    }

    override fun previewSiblings(): List<Node> = _state.value.items.map { it.node }.filterNot { it.isFolder }

    /** 从「最近」里拿掉一个:先从列表去掉,失败了再放回来 */
    fun forget(node: Node) {
        val before = _state.value.items
        _state.update { s -> s.copy(items = s.items.filterNot { it.node.id == node.id }) }
        viewModelScope.launch {
            gopanResult { quick.clearRecent(listOf(node.id)) }
                .onSuccess { send(NodeEffect.Message(UiText.Res(R.string.quick_msg_forgotten))) }
                .onFailure { e ->
                    _state.update { it.copy(items = before) }
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
                .onSuccess { _state.update { it.copy(items = emptyList()) } }
                .onFailure { e -> send(NodeEffect.Message(UiText.Plain(e.message ?: "操作失败"))) }
        }
    }

    override suspend fun afterMutation() {
        load(LoadMode.SILENT)
    }

    companion object {
        /** 与 Routes.Quick 的属性名一致 */
        const val RECENT_ARG = "recent"
    }
}
