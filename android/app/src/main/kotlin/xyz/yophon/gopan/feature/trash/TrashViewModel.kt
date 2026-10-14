package xyz.yophon.gopan.feature.trash

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
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.mergePages
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject

sealed interface TrashDialog {
    data class Actions(val node: Node) : TrashDialog

    data class Details(val node: Node) : TrashDialog

    data class ConfirmPurge(val ids: List<String>, val singleName: String?) : TrashDialog

    /** 清空回收站要确认两次:step = 1 / 2 */
    data class ConfirmPurgeAll(val step: Int) : TrashDialog
}

@Immutable
data class TrashUiState(
    val items: List<Node> = emptyList(),
    val status: ListStatus = ListStatus.Loading,
    val total: Int = 0,
    val nextCursor: String? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val selection: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val busy: Boolean = false,
    val dialog: TrashDialog? = null,
    /** 列表加载时刻(epoch 毫秒),「还剩 N 天」按它算,不必每次重组取 now */
    val nowMillis: Long = 0L,
) {
    val isEmpty: Boolean get() = status == ListStatus.Content && items.isEmpty()
    val selectedNodes: List<Node> get() = items.filter { it.id in selection }
    val allSelected: Boolean get() = items.isNotEmpty() && selection.size >= items.size
}

/** 回收站:列表(分页)、还原、彻底删除(确认)、清空(确认两次)、多选批量 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repo: NodesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = _messages.receiveAsFlow()

    private var loadedVersion = -1L
    private var loadJob: Job? = null

    init {
        load(LoadMode.INITIAL)
    }

    private enum class LoadMode { INITIAL, REFRESH, SILENT }

    fun retry() = load(LoadMode.INITIAL)

    fun refresh() = load(LoadMode.REFRESH)

    fun onResumed() {
        if (_state.value.status != ListStatus.Content) return
        if (loadedVersion != repo.version.value) load(LoadMode.SILENT)
    }

    private fun load(mode: LoadMode) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    status = if (mode == LoadMode.INITIAL) ListStatus.Loading else it.status,
                    isRefreshing = mode == LoadMode.REFRESH,
                    isLoadingMore = false,
                    loadMoreError = null,
                )
            }
            gopanResult { repo.trash(null) }
                .onSuccess { page ->
                    loadedVersion = repo.version.value
                    _state.update {
                        it.copy(
                            items = page.items,
                            total = page.total,
                            nextCursor = page.nextCursor,
                            status = ListStatus.Content,
                            isRefreshing = false,
                            nowMillis = System.currentTimeMillis(),
                            selection = it.selection.intersect(page.items.mapTo(HashSet()) { n -> n.id }),
                        )
                    }
                }
                .onFailure { e ->
                    val message = (e as? GopanException ?: e.toGopanException()).let { it.message ?: it.code }
                    _state.update {
                        if (mode == LoadMode.INITIAL || it.items.isEmpty()) {
                            it.copy(status = ListStatus.Error(message), isRefreshing = false, items = emptyList(), nextCursor = null)
                        } else {
                            it.copy(isRefreshing = false)
                        }
                    }
                    if (mode != LoadMode.INITIAL && _state.value.items.isNotEmpty()) _messages.trySend(UiText.Plain(message))
                }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.isLoadingMore || s.status != ListStatus.Content) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            gopanResult { repo.trash(cursor) }
                .onSuccess { page ->
                    _state.update { it.copy(items = mergePages(it.items, page.items), nextCursor = page.nextCursor, isLoadingMore = false) }
                }
                .onFailure { e ->
                    val ge = e as? GopanException ?: e.toGopanException()
                    _state.update { it.copy(isLoadingMore = false, loadMoreError = ge.message ?: ge.code) }
                }
        }
    }

    // ---- 多选 ----

    fun onItemClick(node: Node) {
        if (_state.value.selectionMode) toggleSelect(node) else showDetails(node)
    }

    fun onItemLongClick(node: Node) {
        _state.update { it.copy(selectionMode = true, selection = it.selection + node.id) }
    }

    fun toggleSelect(node: Node) {
        _state.update {
            val next = if (node.id in it.selection) it.selection - node.id else it.selection + node.id
            it.copy(selection = next, selectionMode = true)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selection = it.items.mapTo(LinkedHashSet()) { n -> n.id }, selectionMode = true) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet(), selectionMode = false) }
    }

    // ---- 弹层 ----

    fun showActions(node: Node) = setDialog(TrashDialog.Actions(node))

    fun showDetails(node: Node) = setDialog(TrashDialog.Details(node))

    fun dismissDialog() {
        if (!_state.value.busy) setDialog(null)
    }

    private fun setDialog(dialog: TrashDialog?) = _state.update { it.copy(dialog = dialog) }

    // ---- 操作 ----

    fun restore(ids: Collection<String>) {
        if (ids.isEmpty()) return
        runMutation(UiText.Res(R.string.trash_msg_restored, ids.size)) { repo.restore(ids.toList()) }
    }

    fun restoreSelected() = restore(_state.value.selection)

    fun requestPurge(nodes: List<Node>) {
        if (nodes.isEmpty()) return
        setDialog(TrashDialog.ConfirmPurge(nodes.map { it.id }, nodes.singleOrNull()?.name))
    }

    fun requestPurgeSelected() = requestPurge(_state.value.selectedNodes)

    fun confirmPurge(ids: List<String>) =
        runMutation(UiText.Res(R.string.trash_msg_purged, ids.size)) { repo.purge(ids) }

    fun requestPurgeAll() = setDialog(TrashDialog.ConfirmPurgeAll(step = 1))

    /** 第一次确认 → 进入第二次;第二次确认 → 真清空 */
    fun confirmPurgeAllStep() {
        val dialog = _state.value.dialog as? TrashDialog.ConfirmPurgeAll ?: return
        if (dialog.step < 2) {
            setDialog(TrashDialog.ConfirmPurgeAll(step = 2))
        } else {
            runMutation(UiText.Res(R.string.trash_msg_purged_all)) { repo.purgeTrash() }
        }
    }

    private fun runMutation(success: UiText, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = gopanResult { block() }
            _state.update { it.copy(busy = false) }
            result.onSuccess {
                _state.update { it.copy(dialog = null, selection = emptySet(), selectionMode = false) }
                _messages.trySend(success)
                load(LoadMode.SILENT)
            }.onFailure { e ->
                val ge = e as? GopanException ?: e.toGopanException()
                _messages.trySend(UiText.Plain(ge.message ?: ge.code))
            }
        }
    }
}
