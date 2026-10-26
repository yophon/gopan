package xyz.yophon.gopan.feature.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.data.ContentHit
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.SearchFilters
import xyz.yophon.gopan.core.data.SearchScope
import xyz.yophon.gopan.core.data.mergeHits
import xyz.yophon.gopan.core.transfer.TransferActions
import xyz.yophon.gopan.core.data.mergePages
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.NodeActionsViewModel
import xyz.yophon.gopan.feature.drive.NodeEffect
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.preview.PreviewSessionStore
import javax.inject.Inject

/** 搜索页的两个标签(与网页端一致);换关键词、清空关键词都不变 */
enum class SearchTab { NAME, CONTENT }

/** 「内容」标签里代替结果的一行提示:不是出错,不给重试 */
enum class ContentNotice {
    /** 关键词不到两个汉字或三个字母 */
    QUERY_TOO_SHORT,

    /** 本站关了内容索引 */
    DISABLED,
}

/** 「内容」标签的结果:没有 total,只靠游标翻页 */
@Immutable
data class ContentResults(
    val hits: List<ContentHit> = emptyList(),
    val status: ListStatus = ListStatus.Content,
    val notice: ContentNotice? = null,
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
) {
    val isEmpty: Boolean get() = status == ListStatus.Content && notice == null && hits.isEmpty()
}

/** 一次搜索要的东西:关键词 + 筛选;结果、游标都属于它 */
@Immutable
data class SearchRequest(val query: String = "", val filters: SearchFilters = SearchFilters()) {
    /** 文件名搜索:有关键词,或者光按条件找 */
    val searchesNames: Boolean get() = query.isNotEmpty() || filters.hasConditions

    /** 内容搜索一定要关键词 */
    val searchesContent: Boolean get() = query.isNotEmpty()
}

@Immutable
data class SearchUiState(
    /** 真正提交过搜索的关键词(trim 后);空串 = 还没输 */
    val query: String = "",
    /** 筛选(doc/30):类型、时间、大小、只搜某个文件夹、排序 */
    val filters: SearchFilters = SearchFilters(),
    val tab: SearchTab = SearchTab.NAME,
    // ---- 「文件名」标签 ----
    val items: List<Node> = emptyList(),
    val status: ListStatus = ListStatus.Content,
    val total: Int = 0,
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    // ---- 「内容」标签 ----
    val content: ContentResults = ContentResults(),
) {
    val request: SearchRequest get() = SearchRequest(query, filters)
    val isIdle: Boolean get() = !request.searchesNames
    val isContentIdle: Boolean get() = !request.searchesContent
    val isEmpty: Boolean get() = !isIdle && status == ListStatus.Content && items.isEmpty()
}

/**
 * 搜索页:关键词 300ms 防抖,结果分页;操作(重命名 / 移动 / 复制 / 删除)与文件页共用基类。
 * 两个标签只搜当前显示的那个:换关键词或筛选时两边的结果都作废,切到另一个标签时它还没搜过才搜。
 * 从某个文件夹点进来的,默认只搜那个文件夹(导航参数 folderId / folderName)。
 * 标签记在 SavedStateHandle 里,进程重建后与界面恢复的关键词一起回来。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    repo: NodesRepository,
    transfers: TransferActions,
    previews: PreviewSessionStore,
    private val savedState: SavedStateHandle,
) : NodeActionsViewModel(repo, transfers, previews) {

    private val _state = MutableStateFlow(SearchUiState(tab = restoredTab(), filters = SearchFilters(scope = restoredScope())))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val input = MutableStateFlow("")
    private val filters = MutableStateFlow(_state.value.filters)
    private val tab = MutableStateFlow(_state.value.tab)

    /** 两个标签的结果各属于哪次搜索;对不上(null)= 还没搜过当前的,切过去要搜 */
    private var nameLoadedFor: SearchRequest? = null
    private var contentLoadedFor: SearchRequest? = null

    /** 两个标签各自上次加载时的变更计数:别处改过东西(重命名、删除…)后回来静默重搜 */
    private var loadedVersion = -1L
    private var contentVersion = -1L

    private var loadMoreJob: Job? = null
    private var contentMoreJob: Job? = null

    init {
        viewModelScope.launch {
            // 换关键词、筛选与换标签都会走到这里;collectLatest 取消上一次还没回来的搜索
            val query = input.map { it.trim() }.debounce(DEBOUNCE_MS).distinctUntilChanged()
            combine(query, filters, tab) { q, f, t -> SearchRequest(q, f) to t }
                .collectLatest { (r, t) -> show(r, t) }
        }
    }

    fun onQueryChange(text: String) {
        input.value = text
    }

    fun onFiltersChange(next: SearchFilters) {
        _state.update { it.copy(filters = next) }
        filters.value = next
    }

    fun clearFilters() = onFiltersChange(SearchFilters())

    fun onTabSelected(selected: SearchTab) {
        if (_state.value.tab == selected) return
        savedState[TAB_KEY] = selected.name
        _state.update { it.copy(tab = selected) }
        tab.value = selected
    }

    fun retry() {
        val s = _state.value
        viewModelScope.launch {
            when (s.tab) {
                SearchTab.NAME -> search(s.request, silent = false)
                SearchTab.CONTENT -> searchContent(s.request, silent = false)
            }
        }
    }

    fun onResumed() {
        val s = _state.value
        when (s.tab) {
            SearchTab.NAME -> {
                if (s.isIdle || s.status != ListStatus.Content) return
                if (loadedVersion != repo.version.value) viewModelScope.launch { search(s.request, silent = true) }
            }
            SearchTab.CONTENT -> {
                if (s.isContentIdle || s.content.status != ListStatus.Content || s.content.notice != null) return
                if (contentVersion != repo.version.value) viewModelScope.launch { searchContent(s.request, silent = true) }
            }
        }
    }

    fun onItemClick(node: Node) {
        if (node.isFolder) send(NodeEffect.OpenFolder(node.id)) else preview(node)
    }

    /** 点内容结果:进预览,左右翻的是这批结果;PDF 直接翻到命中页 */
    fun onHitClick(hit: ContentHit) = preview(hit.node)

    override fun previewSiblings(): List<Node> {
        val s = _state.value
        return when (s.tab) {
            SearchTab.NAME -> s.items
            SearchTab.CONTENT -> s.content.hits.map { it.node }
        }
    }

    override fun previewPdfPages(): Map<String, Int> {
        val s = _state.value
        if (s.tab != SearchTab.CONTENT) return emptyMap()
        return s.content.hits.mapNotNull { h -> h.page?.let { h.node.id to it } }.toMap()
    }

    /** 关键词、筛选或标签变了:搜索变了先把两边的结果作废,再按需搜当前标签 */
    private suspend fun show(r: SearchRequest, t: SearchTab) {
        if (r != _state.value.request) {
            // 在途的翻页属于旧结果,游标也不绑定关键词,必须取消,否则旧页会拼进新列表
            loadMoreJob?.cancel()
            contentMoreJob?.cancel()
            nameLoadedFor = null
            contentLoadedFor = null
            _state.update {
                // 另一个标签也先标成加载中:切过去时不会闪一下上次的结果
                SearchUiState(
                    query = r.query,
                    filters = r.filters,
                    tab = it.tab,
                    status = if (r.searchesNames) ListStatus.Loading else ListStatus.Content,
                    content = ContentResults(status = if (r.searchesContent) ListStatus.Loading else ListStatus.Content),
                )
            }
        }
        when (t) {
            SearchTab.NAME -> when {
                !r.searchesNames -> Unit
                nameLoadedFor != r -> search(r, silent = false)
                loadedVersion != repo.version.value -> search(r, silent = true)
            }
            SearchTab.CONTENT -> when {
                !r.searchesContent -> Unit
                contentLoadedFor != r -> searchContent(r, silent = false)
                contentVersion != repo.version.value && _state.value.content.notice == null -> searchContent(r, silent = true)
            }
        }
    }

    private suspend fun search(r: SearchRequest, silent: Boolean) {
        loadMoreJob?.cancel()
        if (!r.searchesNames) return
        _state.update {
            it.copy(
                status = if (silent) it.status else ListStatus.Loading,
                items = if (silent) it.items else emptyList(),
                nextCursor = null,
                isLoadingMore = false,
                loadMoreError = null,
            )
        }
        gopanResult { repo.search(r.query, null, r.filters) }
            .onSuccess { page ->
                // 期间换了关键词或筛选(重试、回前台的静默重搜不受 collectLatest 管):丢弃
                if (_state.value.request != r) return@onSuccess
                nameLoadedFor = r
                loadedVersion = repo.version.value
                _state.update { it.copy(items = page.items, total = page.total, nextCursor = page.nextCursor, status = ListStatus.Content) }
            }
            .onFailure { e ->
                if (_state.value.request != r) return@onFailure
                val ge = e as? GopanException ?: e.toGopanException()
                if (silent && _state.value.items.isNotEmpty()) {
                    // 静默重搜失败:保留手上的结果,只提示
                    message(UiText.Plain(ge.message ?: ge.code))
                } else {
                    _state.update { it.copy(status = ListStatus.Error(ge.message ?: ge.code), items = emptyList()) }
                }
            }
    }

    private suspend fun searchContent(r: SearchRequest, silent: Boolean) {
        contentMoreJob?.cancel()
        if (!r.searchesContent) return
        _state.update {
            val c = it.content
            it.copy(
                content = c.copy(
                    status = if (silent) c.status else ListStatus.Loading,
                    hits = if (silent) c.hits else emptyList(),
                    notice = if (silent) c.notice else null,
                    nextCursor = null,
                    isLoadingMore = false,
                    loadMoreError = null,
                ),
            )
        }
        gopanResult { repo.searchContent(r.query, null, r.filters) }
            .onSuccess { page ->
                if (_state.value.request != r) return@onSuccess
                contentLoadedFor = r
                contentVersion = repo.version.value
                // 首页也去重:结果列表按文件 id 做 key,重复会让 LazyColumn 直接崩
                _state.update { it.copy(content = ContentResults(hits = mergeHits(emptyList(), page.items), nextCursor = page.nextCursor)) }
            }
            .onFailure { e ->
                if (_state.value.request != r) return@onFailure
                val ge = e as? GopanException ?: e.toGopanException()
                val notice = contentNotice(ge.code)
                when {
                    // 关键词太短 / 本站没开:这就是这次搜索的结果,切标签回来不用再问一次
                    notice != null -> {
                        contentLoadedFor = r
                        contentVersion = repo.version.value
                        _state.update { it.copy(content = ContentResults(notice = notice)) }
                    }
                    silent && _state.value.content.hits.isNotEmpty() -> message(UiText.Plain(ge.message ?: ge.code))
                    else -> _state.update { it.copy(content = ContentResults(status = ListStatus.Error(ge.message ?: ge.code))) }
                }
            }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.isLoadingMore || s.status != ListStatus.Content) return
        val r = s.request
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            gopanResult { repo.search(r.query, cursor, r.filters) }
                .onSuccess { page ->
                    _state.update {
                        // 期间搜索变了:丢弃这页
                        if (it.request != r) it.copy(isLoadingMore = false)
                        else it.copy(items = mergePages(it.items, page.items), nextCursor = page.nextCursor, isLoadingMore = false)
                    }
                }
                .onFailure { e ->
                    val ge = e as? GopanException ?: e.toGopanException()
                    _state.update { it.copy(isLoadingMore = false, loadMoreError = ge.message ?: ge.code) }
                }
        }
    }

    fun loadMoreContent() {
        val s = _state.value
        val c = s.content
        val cursor = c.nextCursor ?: return
        if (c.isLoadingMore || c.status != ListStatus.Content) return
        val r = s.request
        contentMoreJob = viewModelScope.launch {
            _state.update { it.copy(content = it.content.copy(isLoadingMore = true, loadMoreError = null)) }
            gopanResult { repo.searchContent(r.query, cursor, r.filters) }
                .onSuccess { page ->
                    _state.update {
                        if (it.request != r) {
                            it.copy(content = it.content.copy(isLoadingMore = false))
                        } else {
                            it.copy(
                                content = it.content.copy(
                                    hits = mergeHits(it.content.hits, page.items),
                                    nextCursor = page.nextCursor,
                                    isLoadingMore = false,
                                ),
                            )
                        }
                    }
                }
                .onFailure { e ->
                    val ge = e as? GopanException ?: e.toGopanException()
                    _state.update { it.copy(content = it.content.copy(isLoadingMore = false, loadMoreError = ge.message ?: ge.code)) }
                }
        }
    }

    /** 变更后静默重搜当前标签;另一个标签的变更计数对不上,切过去时再重搜 */
    override suspend fun afterMutation() {
        val s = _state.value
        when (s.tab) {
            SearchTab.NAME -> if (!s.isIdle) search(s.request, silent = true)
            SearchTab.CONTENT -> if (!s.isContentIdle && s.content.notice == null) searchContent(s.request, silent = true)
        }
    }

    private fun restoredTab(): SearchTab =
        savedState.get<String>(TAB_KEY)?.let { name -> SearchTab.entries.firstOrNull { it.name == name } } ?: SearchTab.NAME

    /** 从文件夹点进来的:默认只搜那个文件夹 */
    private fun restoredScope(): SearchScope? {
        val id = savedState.get<String>(FOLDER_ID_KEY)?.takeIf { it.isNotEmpty() } ?: return null
        return SearchScope(id, savedState.get<String>(FOLDER_NAME_KEY).orEmpty())
    }

    private fun contentNotice(code: String): ContentNotice? = when (code) {
        GopanErrors.QUERY_TOO_SHORT -> ContentNotice.QUERY_TOO_SHORT
        GopanErrors.CONTENT_SEARCH_DISABLED -> ContentNotice.DISABLED
        else -> null
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        private const val TAB_KEY = "search.tab"

        /** Routes.Search 的导航参数 */
        const val FOLDER_ID_KEY = "folderId"
        const val FOLDER_NAME_KEY = "folderName"
    }
}
