package xyz.yophon.gopan.feature.drive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
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
import xyz.yophon.gopan.core.transfer.EnqueueResult
import xyz.yophon.gopan.core.transfer.TransferActions
import xyz.yophon.gopan.core.transfer.TransferEvent
import android.net.Uri
import java.io.File
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
    /** 换排序后的新列表到达时 +1,界面据此滚回顶部(静默刷新不动滚动位置) */
    val scrollToTopToken: Int = 0,
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
    transfers: TransferActions,
) : NodeActionsViewModel(repo, transfers) {

    val parentId: String? = savedStateHandle[FOLDER_ID_ARG]

    private val _state = MutableStateFlow(DriveUiState(parentId = parentId))
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    /** 上次成功加载时的仓库变更计数;回到前台时不等就静默刷新 */
    private var loadedVersion = -1L
    private var prefsReady = false
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var crumbJob: Job? = null

    /** 本次列表数据里的缩略图地址是否已因过期触发过刷新(每次加载只触发一次,避免反复刷) */
    private var thumbRefreshRequested = false

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
                        load(
                            mode = if (first || _state.value.items.isEmpty()) LoadMode.INITIAL else LoadMode.SILENT,
                            scrollToTop = !first,
                        )
                    }
                }
        }
        if (parentId != null) loadBreadcrumb(refreshSelf = false)
        // 上传落进本目录:静默刷新(一批文件陆续完成时合并成一次)
        viewModelScope.launch {
            transfers.events
                .filter { it is TransferEvent.UploadDone && it.task.parentId == parentId }
                .debounce(UPLOAD_REFRESH_DEBOUNCE_MS)
                .collect { if (_state.value.status == ListStatus.Content) load(LoadMode.SILENT) }
        }
    }

    // ---- 加载 ----

    private enum class LoadMode { INITIAL, REFRESH, SILENT }

    fun retry() = load(LoadMode.INITIAL)

    fun refresh() = load(LoadMode.REFRESH)

    /** 页面回到前台:别处(子目录、回收站、搜索)改过东西,或列表放久了(缩略图地址快过期)就静默刷新 */
    fun onResumed() {
        val s = _state.value
        if (s.status != ListStatus.Content) return
        if (loadedVersion != repo.version.value || (!s.fromCache && isStale(s.fetchedAt))) load(LoadMode.SILENT)
    }

    /**
     * 某张缩略图在线加载失败。列表拉取超过 [STALE_AFTER_MS] 时多半是预签名地址过期:静默刷新一次首页
     * (只在还没翻页时刷,翻过页的长列表刷新会丢掉后面几页,留给用户下拉刷新)。
     */
    fun onThumbError() {
        val s = _state.value
        if (thumbRefreshRequested || s.status != ListStatus.Content || s.fromCache) return
        if (!isStale(s.fetchedAt) || s.isRefreshing || loadJob?.isActive == true) return
        if (s.nextCursor != null && s.items.size > FIRST_PAGE_HINT) return
        thumbRefreshRequested = true
        load(LoadMode.SILENT)
    }

    private fun isStale(fetchedAt: Long) = fetchedAt > 0 && System.currentTimeMillis() - fetchedAt > STALE_AFTER_MS

    private fun load(mode: LoadMode, scrollToTop: Boolean = false) {
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
                val current = _state.value
                if (listing.fromCache && mode != LoadMode.INITIAL && current.items.isNotEmpty() && !current.fromCache) {
                    // 刷新失败而手上已有联网拿到的列表(可能已翻了好几页):保留它,只提示,不用缓存的首页覆盖
                    _state.update { it.copy(isRefreshing = false) }
                    if (mode == LoadMode.REFRESH) message(UiText.Plain(listing.error?.message ?: OFFLINE_FALLBACK_MESSAGE))
                    return@onSuccess
                }
                loadedVersion = repo.version.value
                thumbRefreshRequested = false
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
                        scrollToTopToken = if (scrollToTop) it.scrollToTopToken + 1 else it.scrollToTopToken,
                    )
                }
                // 联网成功:刷新面包屑(当前文件夹可能在别处被改名;首次解析失败的占位也借机补上)
                if (!listing.fromCache && parentId != null) {
                    val needSelf = mode != LoadMode.INITIAL || _state.value.crumbs.any { c -> c.name.isEmpty() }
                    loadBreadcrumb(refreshSelf = needSelf)
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
        if (s.isLoadingMore || s.status != ListStatus.Content || s.fromCache || loadJob?.isActive == true) return
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

    /**
     * 面包屑:从顶层到当前文件夹。[refreshSelf] 时先联网取一次当前文件夹(拿到最新名字),再沿父链解析;
     * 解析失败只在还没有面包屑时显示占位,已有的保留。
     */
    private fun loadBreadcrumb(refreshSelf: Boolean) {
        val folderId = parentId ?: return
        if (crumbJob?.isActive == true) return
        crumbJob = viewModelScope.launch {
            if (refreshSelf) gopanResult { repo.node(folderId) }
            val chain = gopanResult { repo.ancestors(folderId) }.getOrNull()
            val crumbs = chain?.map { Crumb(it.id, it.name) }?.takeIf { it.isNotEmpty() }
            _state.update {
                when {
                    crumbs != null -> it.copy(crumbs = crumbs)
                    it.crumbs.isEmpty() -> it.copy(crumbs = listOf(Crumb(folderId, "")))
                    else -> it
                }
            }
        }
    }

    // ---- 排序 / 视图 ----

    fun selectSort(order: DriveSortOrder) {
        val next = DrivePrefsReducer.selectOrder(_state.value.sort, order)
        savePref { prefs.setDriveSort(next) }
    }

    fun toggleViewMode() {
        val next = DrivePrefsReducer.toggleViewMode(_state.value.viewMode)
        savePref { prefs.setDriveViewMode(next) }
    }

    /** 偏好写入失败(磁盘满、文件损坏)只提示,不让异常把 App 带崩 */
    private fun savePref(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "写列表偏好失败")
                message(UiText.Res(R.string.drive_pref_save_failed))
            }
        }
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

    /** 成功提示用服务端返回的名字:同名时服务端会自动改成「名称 (1)」 */
    fun createFolder(name: String) =
        runMutationFor(inputName = name, block = { repo.createFolder(parentId, name) }) { created ->
            UiText.Res(R.string.drive_msg_created, created.name)
        }

    /** 多选里恰好选了一项时可以直接重命名(与网页端一致) */
    fun renameSelected() {
        val node = _state.value.selectedNodes.singleOrNull() ?: return
        requestRename(node)
    }

    fun deleteSelected() = requestDelete(_state.value.selectedNodes)

    fun downloadSelected() {
        val nodes = _state.value.selectedNodes
        clearSelection()
        download(nodes)
    }

    fun packSelected() {
        val nodes = _state.value.selectedNodes
        clearSelection()
        pack(nodes)
    }

    // ---- 上传 ----

    /** SAF 多选 / 相册选择器选中的文件 */
    fun uploadUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val r = transfers.uploadUris(uris, parentId)
            reportEnqueue(r)
        }
    }

    /** App 内拍的照片(私有文件,传完删) */
    fun uploadCameraFile(file: File) {
        viewModelScope.launch { reportEnqueue(transfers.uploadOwnedFiles(listOf(file), parentId)) }
    }

    private fun reportEnqueue(r: EnqueueResult) {
        if (r.queued > 0) {
            message(UiText.Res(R.string.transfer_msg_queued_upload, r.queued))
            send(NodeEffect.TransferQueued)
        }
        if (r.emptySkipped > 0) message(UiText.Res(R.string.transfer_msg_empty_skipped, r.emptySkipped))
        if (r.unreadable > 0) message(UiText.Res(R.string.transfer_msg_pick_failed))
    }

    fun moveSelected() = requestMove(_state.value.selection)

    fun copySelected() = requestCopy(_state.value.selection)

    override suspend fun afterMutation() {
        clearSelection()
        load(LoadMode.SILENT)
    }

    companion object {
        /** 与 Routes.Folder 的属性名一致 */
        const val FOLDER_ID_ARG = "folderId"

        /** 预签名缩略图 15 分钟过期;列表超过 10 分钟就视为"旧了" */
        const val STALE_AFTER_MS = 10 * 60 * 1000L

        /** 服务端首页条数的大致值:超过它说明用户已经翻过页 */
        private const val FIRST_PAGE_HINT = 60

        private const val OFFLINE_FALLBACK_MESSAGE = "网络不可用,列表未刷新"

        private const val UPLOAD_REFRESH_DEBOUNCE_MS = 600L
    }
}
