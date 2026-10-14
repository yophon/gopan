package xyz.yophon.gopan.feature.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.transfer.TransferActions
import xyz.yophon.gopan.core.data.mergePages
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.NodeActionsViewModel
import xyz.yophon.gopan.feature.drive.NodeEffect
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject

@Immutable
data class SearchUiState(
    /** 真正提交过搜索的关键词(trim 后);空串 = 还没搜 */
    val query: String = "",
    val items: List<Node> = emptyList(),
    val status: ListStatus = ListStatus.Content,
    val total: Int = 0,
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
) {
    val isIdle: Boolean get() = query.isEmpty()
    val isEmpty: Boolean get() = !isIdle && status == ListStatus.Content && items.isEmpty()
}

/** 搜索页:关键词 300ms 防抖,结果分页;操作(重命名 / 移动 / 复制 / 删除)与文件页共用基类 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    repo: NodesRepository,
    transfers: TransferActions,
) : NodeActionsViewModel(repo, transfers) {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val input = MutableStateFlow("")
    private var loadedVersion = -1L
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch {
            input.map { it.trim() }
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { q -> search(q, silent = false) }
        }
    }

    fun onQueryChange(text: String) {
        input.value = text
    }

    fun retry() {
        viewModelScope.launch { search(_state.value.query, silent = false) }
    }

    fun onResumed() {
        val s = _state.value
        if (s.isIdle || s.status != ListStatus.Content) return
        if (loadedVersion != repo.version.value) viewModelScope.launch { search(s.query, silent = true) }
    }

    fun onItemClick(node: Node) {
        if (node.isFolder) send(NodeEffect.OpenFolder(node.id)) else showDetails(node)
    }

    private suspend fun search(q: String, silent: Boolean) {
        // 换了关键词(或重搜):在途的翻页属于旧结果,游标也不绑定关键词,必须取消,否则旧页会拼进新列表
        loadMoreJob?.cancel()
        if (q.isEmpty()) {
            _state.update { SearchUiState() }
            return
        }
        _state.update {
            it.copy(
                query = q,
                status = if (silent) it.status else ListStatus.Loading,
                items = if (silent) it.items else emptyList(),
                nextCursor = null,
                isLoadingMore = false,
                loadMoreError = null,
            )
        }
        gopanResult { repo.search(q, null) }
            .onSuccess { page ->
                loadedVersion = repo.version.value
                _state.update { it.copy(items = page.items, total = page.total, nextCursor = page.nextCursor, status = ListStatus.Content) }
            }
            .onFailure { e ->
                val ge = e as? GopanException ?: e.toGopanException()
                if (silent && _state.value.items.isNotEmpty()) {
                    // 静默重搜失败:保留手上的结果,只提示
                    message(UiText.Plain(ge.message ?: ge.code))
                } else {
                    _state.update { it.copy(status = ListStatus.Error(ge.message ?: ge.code), items = emptyList()) }
                }
            }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.isLoadingMore || s.status != ListStatus.Content) return
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            gopanResult { repo.search(s.query, cursor) }
                .onSuccess { page ->
                    _state.update {
                        // 期间关键词变了:丢弃这页
                        if (it.query != s.query) it.copy(isLoadingMore = false)
                        else it.copy(items = mergePages(it.items, page.items), nextCursor = page.nextCursor, isLoadingMore = false)
                    }
                }
                .onFailure { e ->
                    val ge = e as? GopanException ?: e.toGopanException()
                    _state.update { it.copy(isLoadingMore = false, loadMoreError = ge.message ?: ge.code) }
                }
        }
    }

    override suspend fun afterMutation() {
        val q = _state.value.query
        if (q.isNotEmpty()) search(q, silent = true)
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
