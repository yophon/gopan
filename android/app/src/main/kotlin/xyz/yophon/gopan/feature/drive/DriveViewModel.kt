package xyz.yophon.gopan.feature.drive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.mergePages
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.core.prefs.DriveSort
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import xyz.yophon.gopan.core.prefs.DriveViewMode
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import javax.inject.Inject

/** 文件页(根目录或某个文件夹)弹层:在共享的 [NodeDialog] 之外多两个 */
sealed interface DriveDialog : NodeDialog {
    data object CreateFolder : DriveDialog

    data object FabMenu : DriveDialog
}

@Immutable
data class DriveUiState(
    val parentId: String?,
    /** 从顶层到当前文件夹(根不在内);根目录为空 */
    val crumbs: List<Crumb> = emptyList(),
    val items: List<Node> = emptyList(),
    val status: ListStatus = ListStatus.Loading,
    val total: Int = 0,
    val nextCursor: String? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    /** 当前显示的是缓存(联网失败回退);[fetchedAt] 是缓存写入时刻,[offlineMessage] 是失败原因 */
    val fromCache: Boolean = false,
    val fetchedAt: Long = 0L,
    val offlineMessage: String? = null,
    val sort: DriveSort = DriveSort(),
    val viewMode: DriveViewMode = DriveViewMode.LIST,
    val selection: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
) {
    val isRoot: Boolean get() = parentId == null
    val isEmpty: Boolean get() = status == ListStatus.Content && items.isEmpty()
    val selectedNodes: List<Node> get() = items.filter { it.id in selection }
    val allSelected: Boolean get() = items.isNotEmpty() && selection.size >= items.size
}

/**
 * 一个目录一个 ViewModel(随导航栈条目存活):列表 + 分页 + 面包屑 + 排序/视图偏好 + 多选 + 节点操作。
 * 路由参数:Routes.Folder.folderId;根目录(Routes.Drive)没有参数。
 */
@HiltViewModel
class DriveViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repo: NodesRepository,
    private val prefs: UserPreferencesRepository,
) : NodeActionsViewModel(repo) {

    val parentId: String? = savedStateHandle[FOLDER_ID_ARG]

    private val _state = MutableStateFlow(DriveUiState(parentId = parentId))
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    /** 上次成功加载时的仓库变更计数;回到前台时不等就静默刷新 */
    private var loadedVersion = -1L
    private var prefsReady = false
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.preferences
                .map { it.driveSort to it.driveViewMode }
                .distinctUntilChanged()
                .collect { (sort, viewMode) ->
                    val first = !prefsReady
                    val sortChanged = sort != _state.value.sort
                    prefsReady = true
                    _state.update { it.copy(sort = sort, viewMode = viewMode) }
                    if (first || sortChanged) {
                        load(if (first || _state.value.items.isEmpty()) LoadMode.INITIAL else LoadMode.SILENT)
                    }
                }
        }
        if (parentId != null) viewModelScope.launch { loadBreadcrumb(parentId) }
    }

    // ---- 加载 ----

    private enum class LoadMode { INITIAL, REFRESH, SILENT }

    fun retry() = load(LoadMode.INITIAL)

    fun refresh() = load(LoadMode.REFRESH)

    /** 页面回到前台:别处(子目录、回收站、搜索)改过东西就静默刷新 */
    fun onResumed() {
        if (_state.value.status != ListStatus.Content) return
        if (loadedVersion != repo.version.value) load(LoadMode.SILENT)
    }

    private fun load(mode: LoadMode) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            val sort = _state.value.sort
            _state.update {
                it.copy(
                    status = if (mode == LoadMode.INITIAL) ListStatus.Loading else it.status,
                    isRefreshing = mode == LoadMode.REFRESH,
                    isLoadingMore = false,
                    loadMoreError = null,
                )
            }
            val result = gopanResult { repo.firstPage(parentId, sort.order, sort.desc) }
            result.onSuccess { listing ->
                loadedVersion = repo.version.value
                _state.update {
                    it.copy(
                        items = listing.page.items,
                        total = listing.page.total,
                        nextCursor = listing.page.nextCursor,
                        status = ListStatus.Content,
                        isRefreshing = false,
                        fromCache = listing.fromCache,
                        fetchedAt = listing.fetchedAt,
                        offlineMessage = listing.error?.message,
                        // 刷新后列表可能变了,去掉已不存在的选中项
                        selection = it.selection.intersect(listing.page.items.mapTo(HashSet()) { n -> n.id }),
                    )
                }
            }.onFailure { e ->
                val ge = e as? GopanException ?: e.toGopanException()
                val message = ge.message ?: ge.code
                _state.update {
                    if (mode == LoadMode.INITIAL || it.items.isEmpty()) {
                        it.copy(status = ListStatus.Error(message), isRefreshing = false, items = emptyList(), nextCursor = null)
                    } else {
                        it.copy(isRefreshing = false)
                    }
                }
                if (mode != LoadMode.INITIAL && _state.value.items.isNotEmpty()) message(UiText.Plain(message))
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.isLoadingMore || s.status != ListStatus.Content || s.fromCache) return
        loadMoreJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            val result = gopanResult { repo.children(parentId, cursor, s.sort.order, s.sort.desc) }
            result.onSuccess { page ->
                _state.update {
                    it.copy(items = mergePages(it.items, page.items), nextCursor = page.nextCursor, isLoadingMore = false)
                }
            }.onFailure { e ->
                val ge = e as? GopanException ?: e.toGopanException()
                _state.update { it.copy(isLoadingMore = false, loadMoreError = ge.message ?: ge.code) }
            }
        }
    }

    private suspend fun loadBreadcrumb(folderId: String) {
        val chain = gopanResult { repo.ancestors(folderId) }.getOrNull()
        val crumbs = chain?.map { Crumb(it.id, it.name) }?.takeIf { it.isNotEmpty() }
            ?: listOf(Crumb(folderId, ""))
        _state.update { it.copy(crumbs = crumbs) }
    }

    // ---- 排序 / 视图 ----

    fun selectSort(order: DriveSortOrder) {
        val next = DrivePrefsReducer.selectOrder(_state.value.sort, order)
        viewModelScope.launch { prefs.setDriveSort(next) }
    }

    fun toggleViewMode() {
        val next = DrivePrefsReducer.toggleViewMode(_state.value.viewMode)
        viewModelScope.launch { prefs.setDriveViewMode(next) }
    }

    // ---- 多选 ----

    fun onItemClick(node: Node) {
        if (_state.value.selectionMode) {
            toggleSelect(node)
        } else if (node.isFolder) {
            send(NodeEffect.OpenFolder(node.id))
        } else {
            showDetails(node)
        }
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

    // ---- 操作 ----

    fun openFabMenu() = showDialog(DriveDialog.FabMenu)

    fun requestCreateFolder() = showDialog(DriveDialog.CreateFolder)

    fun createFolder(name: String) =
        runMutation(UiText.Res(R.string.drive_msg_created, name), inputName = name) { repo.createFolder(parentId, name) }

    fun deleteSelected() = requestDelete(_state.value.selectedNodes)

    fun moveSelected() = requestMove(_state.value.selection)

    fun copySelected() = requestCopy(_state.value.selection)

    override suspend fun afterMutation() {
        clearSelection()
        load(LoadMode.SILENT)
    }

    companion object {
        /** 与 Routes.Folder 的属性名一致 */
        const val FOLDER_ID_ARG = "folderId"
    }
}
