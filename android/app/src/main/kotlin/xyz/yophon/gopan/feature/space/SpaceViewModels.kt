package xyz.yophon.gopan.feature.space

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
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.space.DuplicateGroup
import xyz.yophon.gopan.core.data.space.DuplicatePage
import xyz.yophon.gopan.core.data.space.SpaceCategory
import xyz.yophon.gopan.core.data.space.SpaceFile
import xyz.yophon.gopan.core.data.space.SpaceFolderLevel
import xyz.yophon.gopan.core.data.space.SpaceIdle
import xyz.yophon.gopan.core.data.space.SpaceOverview
import xyz.yophon.gopan.core.data.space.SpaceRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.notifications.NotificationNav
import xyz.yophon.gopan.feature.notifications.NotificationsLogic
import javax.inject.Inject

/*
 * 「我的 → 空间」(doc/23 三.1)的各个标签,一个标签一个 ViewModel(都挂在空间页的导航条目上,切标签不丢)。
 * 第一次切到某个标签才建它的 ViewModel、才查;别处改过网盘(NodesRepository.version 变了)后回到这个标签静默重查。
 * 每个标签上次的结果留在 ScreenCache 里:再进空间页先画出来,同时静默重查(键固定的标签用 mirror 跟着状态写,
 * 键随状态变的——文件夹按层、大文件按类型筛选——在拿到数据和本地改动后显式写)。
 * 清理一律移进回收站(NodesRepository.delete,30 天内能还原、记进动态)。
 */

sealed interface SpaceEffect {
    data class Message(val text: UiText) : SpaceEffect

    /** 在文件页打开:文件夹进去,文件进所在文件夹再预览 */
    data class Open(val nav: NotificationNav.Drive) : SpaceEffect
}

/** 打开一个节点的跳转(与通知的 NODE 目标同一套) */
internal fun openNav(node: Node): NotificationNav.Drive =
    NotificationsLogic.refineNodeNav(NotificationNav.Drive(folderId = node.parentId, previewId = node.id), node.isFolder)

/** 各标签共用:状态、静默重查、一次性提示 */
abstract class SpaceTabViewModel(
    protected val nodes: NodesRepository,
    protected val cache: ScreenCache,
) : ViewModel() {
    protected val _effects = Channel<SpaceEffect>(Channel.BUFFERED)
    val effects: Flow<SpaceEffect> = _effects.receiveAsFlow()

    /** 上次查完时的网盘变更计数 */
    protected var loadedVersion = -1L
    private var loadJob: Job? = null

    protected abstract val status: ListStatus

    /** 查一次;[silent] 时不清掉已有内容 */
    protected abstract suspend fun fetch(silent: Boolean)

    protected fun load(silent: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val version = nodes.version.value
            fetch(silent)
            if (status == ListStatus.Content) loadedVersion = version
        }
    }

    fun retry() = load(silent = false)

    /** 标签露出来 / 回到前台:别处改过网盘就静默重查 */
    fun onShown() {
        if (status != ListStatus.Content || loadJob?.isActive == true) return
        if (loadedVersion != nodes.version.value) load(silent = true)
    }

    protected fun message(text: UiText) {
        _effects.trySend(SpaceEffect.Message(text))
    }

    protected fun errorText(e: Throwable): String = e.message ?: "加载失败"

    fun open(node: Node) {
        _effects.trySend(SpaceEffect.Open(openNav(node)))
    }
}

// ---------- 总览 ----------

@Immutable
data class SpaceOverviewUiState(
    val status: ListStatus = ListStatus.Loading,
    val overview: SpaceOverview? = null,
    val refreshing: Boolean = false,
    val confirmPurge: Boolean = false,
    val purging: Boolean = false,
    /** 清空历史版本(doc/24):先确认 */
    val confirmPurgeVersions: Boolean = false,
    val purgingVersions: Boolean = false,
)

/** 总览:已用 / 配额、按类型、回收站(清空)、历史版本(清空)、重复文件的合计 */
@HiltViewModel
class SpaceOverviewViewModel @Inject constructor(
    private val repo: SpaceRepository,
    nodes: NodesRepository,
    cache: ScreenCache,
) : SpaceTabViewModel(nodes, cache) {

    private val _state = MutableStateFlow(SpaceOverviewUiState())
    val state: StateFlow<SpaceOverviewUiState> = _state.asStateFlow()

    override val status: ListStatus get() = _state.value.status

    init {
        cache.mirror(viewModelScope, CACHE_KEY, _state) { s -> s.overview.takeIf { s.status == ListStatus.Content } }
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    override suspend fun fetch(silent: Boolean) {
        // 上次的总览先画出来,再静默重查
        val cached = if (silent) null else cache[CACHE_KEY]
        when {
            cached != null -> _state.update { it.copy(status = ListStatus.Content, overview = cached) }
            !silent -> _state.update { it.copy(status = ListStatus.Loading) }
        }
        gopanResult { repo.overview() }
            .onSuccess { o -> _state.update { it.copy(status = ListStatus.Content, overview = o, refreshing = false) } }
            .onFailure { e ->
                if (_state.value.overview != null) {
                    _state.update { it.copy(refreshing = false) }
                    message(UiText.Plain(errorText(e)))
                } else {
                    _state.update { it.copy(status = ListStatus.Error(errorText(e)), refreshing = false) }
                }
            }
    }

    fun requestPurgeTrash() {
        if ((_state.value.overview?.trashBytes ?: 0) <= 0 && (_state.value.overview?.trashFiles ?: 0) <= 0) return
        _state.update { it.copy(confirmPurge = true) }
    }

    fun dismissPurge() {
        if (!_state.value.purging) _state.update { it.copy(confirmPurge = false) }
    }

    fun confirmPurgeTrash() {
        if (_state.value.purging) return
        _state.update { it.copy(purging = true) }
        viewModelScope.launch {
            gopanResult { nodes.purgeTrash() }
                .onSuccess {
                    _state.update { it.copy(purging = false, confirmPurge = false) }
                    message(UiText.Res(R.string.trash_msg_purged_all))
                    load(silent = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(purging = false) }
                    message(UiText.Plain(e.message ?: "操作失败"))
                }
        }
    }

    fun requestPurgeVersions() {
        if ((_state.value.overview?.versionFiles ?: 0) <= 0) return
        _state.update { it.copy(confirmPurgeVersions = true) }
    }

    fun dismissPurgeVersions() {
        if (!_state.value.purgingVersions) _state.update { it.copy(confirmPurgeVersions = false) }
    }

    fun confirmPurgeVersions() {
        if (_state.value.purgingVersions) return
        _state.update { it.copy(purgingVersions = true) }
        viewModelScope.launch {
            gopanResult { repo.purgeVersions() }
                .onSuccess {
                    _state.update { it.copy(purgingVersions = false, confirmPurgeVersions = false) }
                    message(UiText.Res(R.string.space_versions_purged))
                    load(silent = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(purgingVersions = false) }
                    message(UiText.Plain(e.message ?: "操作失败"))
                }
        }
    }

    companion object {
        val CACHE_KEY = ScreenKey<SpaceOverview>("space/overview")
    }
}

// ---------- 文件夹 ----------

/** 下钻的一级:[id] 为 null 是根目录 */
@Immutable
data class FolderCrumb(val id: String?, val name: String)

@Immutable
data class SpaceFoldersUiState(
    val status: ListStatus = ListStatus.Loading,
    val stack: List<FolderCrumb> = listOf(FolderCrumb(null, "")),
    val level: SpaceFolderLevel? = null,
    val refreshing: Boolean = false,
) {
    val canGoUp: Boolean get() = stack.size > 1
}

/** 文件夹:从根开始逐层看哪个最大,点进去下钻,返回回上一层 */
@HiltViewModel
class SpaceFoldersViewModel @Inject constructor(
    private val repo: SpaceRepository,
    nodes: NodesRepository,
    cache: ScreenCache,
) : SpaceTabViewModel(nodes, cache) {

    private val _state = MutableStateFlow(SpaceFoldersUiState())
    val state: StateFlow<SpaceFoldersUiState> = _state.asStateFlow()

    override val status: ListStatus get() = _state.value.status

    init {
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    override suspend fun fetch(silent: Boolean) {
        val parentId = _state.value.stack.last().id
        val key = levelKey(parentId)
        // 看过的这一层(下钻、返回上一层都算)先画出来,再静默重查
        val cached = if (silent) null else cache[key]
        when {
            cached != null -> _state.update { it.copy(status = ListStatus.Content, level = cached) }
            !silent -> _state.update { it.copy(status = ListStatus.Loading, level = null) }
        }
        gopanResult { repo.folders(parentId) }
            .onSuccess { level ->
                cache.put(key, level)
                _state.update { it.copy(status = ListStatus.Content, level = level, refreshing = false) }
            }
            .onFailure { e ->
                if ((silent || cached != null) && _state.value.level != null) {
                    _state.update { it.copy(refreshing = false) }
                    message(UiText.Plain(errorText(e)))
                } else {
                    _state.update { it.copy(status = ListStatus.Error(errorText(e)), refreshing = false) }
                }
            }
    }

    fun enter(folder: Node) {
        if (!folder.isFolder) return
        _state.update { it.copy(stack = it.stack + FolderCrumb(folder.id, folder.name)) }
        load(silent = false)
    }

    /** 回上一层;已在根目录返回 false(交给系统返回) */
    fun up(): Boolean {
        if (!_state.value.canGoUp) return false
        _state.update { it.copy(stack = it.stack.dropLast(1)) }
        load(silent = false)
        return true
    }

    private fun levelKey(parentId: String?) = ScreenKey<SpaceFolderLevel>("space/folders/${parentId.orEmpty()}")
}

// ---------- 可勾选的文件列表:大文件、很久没动、空文件夹 ----------

@Immutable
data class SpaceFilesUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<SpaceFile> = emptyList(),
    val selection: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    val confirmDelete: Boolean = false,
    val deleting: Boolean = false,
    /** 大文件的类型筛选;null = 全部 */
    val category: SpaceCategory? = null,
) {
    val selectedBytes: Long get() = SpaceLogic.selectedBytes(items, selection)
    val allSelected: Boolean get() = items.isNotEmpty() && items.all { it.node.id in selection }
}

/** 一张可勾选的清单:勾选后移进回收站 */
abstract class SpaceFilesViewModel(nodes: NodesRepository, cache: ScreenCache) : SpaceTabViewModel(nodes, cache) {

    protected val _state = MutableStateFlow(SpaceFilesUiState())
    val state: StateFlow<SpaceFilesUiState> = _state.asStateFlow()

    override val status: ListStatus get() = _state.value.status

    protected abstract suspend fun query(): List<SpaceFile>

    /** 这张清单当前的缓存键(大文件按类型筛选分开存) */
    protected abstract fun cacheKey(): ScreenKey<List<SpaceFile>>

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    override suspend fun fetch(silent: Boolean) {
        val key = cacheKey()
        // 上次的清单先画出来,再静默重查
        val cached = if (silent) null else cache[key]
        when {
            cached != null -> _state.update { it.copy(status = ListStatus.Content, items = cached) }
            !silent -> _state.update { it.copy(status = ListStatus.Loading) }
        }
        gopanResult { query() }
            .onSuccess { items ->
                cache.put(key, items)
                val ids = items.mapTo(HashSet()) { it.node.id }
                _state.update { it.copy(status = ListStatus.Content, items = items, selection = it.selection intersect ids, refreshing = false) }
            }
            .onFailure { e ->
                if ((silent || cached != null) && _state.value.status == ListStatus.Content) {
                    _state.update { it.copy(refreshing = false) }
                    message(UiText.Plain(errorText(e)))
                } else {
                    _state.update { it.copy(status = ListStatus.Error(errorText(e)), refreshing = false, items = emptyList(), selection = emptySet()) }
                }
            }
    }

    fun toggle(id: String) {
        _state.update { s -> s.copy(selection = if (id in s.selection) s.selection - id else s.selection + id) }
    }

    fun selectAll() {
        _state.update { s -> s.copy(selection = if (s.allSelected) emptySet() else s.items.mapTo(LinkedHashSet()) { it.node.id }) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun requestDelete() {
        if (_state.value.selection.isEmpty()) return
        _state.update { it.copy(confirmDelete = true) }
    }

    fun dismissDelete() {
        if (!_state.value.deleting) _state.update { it.copy(confirmDelete = false) }
    }

    fun confirmDelete() {
        val s = _state.value
        val ids = s.items.map { it.node.id }.filter { it in s.selection }
        if (ids.isEmpty() || s.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            gopanResult { nodes.delete(ids) }
                .onSuccess {
                    loadedVersion = nodes.version.value
                    val gone = ids.toSet()
                    _state.update { st ->
                        st.copy(deleting = false, confirmDelete = false, items = st.items.filterNot { it.node.id in gone }, selection = emptySet())
                    }
                    // 删掉的不留在缓存里,下次进来不会先闪出来
                    if (_state.value.status == ListStatus.Content) cache.put(cacheKey(), _state.value.items)
                    message(UiText.Res(R.string.space_msg_deleted, ids.size))
                }
                .onFailure { e ->
                    _state.update { it.copy(deleting = false) }
                    message(UiText.Plain(e.message ?: "操作失败"))
                }
        }
    }
}

/** 大文件:前 100 个,可按类型筛 */
@HiltViewModel
class LargeFilesViewModel @Inject constructor(
    private val repo: SpaceRepository,
    nodes: NodesRepository,
    cache: ScreenCache,
) : SpaceFilesViewModel(nodes, cache) {
    init {
        load(silent = false)
    }

    override suspend fun query(): List<SpaceFile> = repo.largeFiles(_state.value.category)

    override fun cacheKey() = ScreenKey<List<SpaceFile>>("space/large/${_state.value.category?.name ?: "ALL"}")

    fun setCategory(category: SpaceCategory?) {
        if (_state.value.category == category) return
        _state.update { it.copy(category = category, items = emptyList(), selection = emptySet()) }
        load(silent = false)
    }
}

/** 很久没动:一年以上没改过、不小于 100 MB */
@HiltViewModel
class StaleFilesViewModel @Inject constructor(
    private val repo: SpaceRepository,
    nodes: NodesRepository,
    cache: ScreenCache,
) : SpaceFilesViewModel(nodes, cache) {
    init {
        load(silent = false)
    }

    override suspend fun query(): List<SpaceFile> = repo.staleFiles()

    override fun cacheKey() = CACHE_KEY

    companion object {
        val CACHE_KEY = ScreenKey<List<SpaceFile>>("space/stale")
    }
}

/** 空文件夹:子树里一个文件都没有的(只列最上面一层) */
@HiltViewModel
class EmptyFoldersViewModel @Inject constructor(
    private val repo: SpaceRepository,
    nodes: NodesRepository,
    cache: ScreenCache,
) : SpaceFilesViewModel(nodes, cache) {
    init {
        load(silent = false)
    }

    override suspend fun query(): List<SpaceFile> = repo.emptyFolders()

    override fun cacheKey() = CACHE_KEY

    companion object {
        val CACHE_KEY = ScreenKey<List<SpaceFile>>("space/empty-folders")
    }
}

// ---------- 重复文件 ----------

@Immutable
data class DuplicatesUiState(
    val status: ListStatus = ListStatus.Loading,
    val groups: List<DuplicateGroup> = emptyList(),
    val nextOffset: Int? = null,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val selection: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    val confirmDelete: Boolean = false,
    val deleting: Boolean = false,
) {
    val selectedCount: Int get() = SpaceLogic.duplicateSelectedCount(groups, selection)
    val selectedBytes: Long get() = SpaceLogic.duplicateSelectedBytes(groups, selection)
    val fullySelectedGroups: Int get() = SpaceLogic.fullySelectedGroups(groups, selection)
}

/** 重复文件:按「删掉多余的能省多少」排的组,默认每组留最早的一份、其余勾上;翻页按 nextOffset */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val repo: SpaceRepository,
    nodes: NodesRepository,
    cache: ScreenCache,
) : SpaceTabViewModel(nodes, cache) {

    private val _state = MutableStateFlow(DuplicatesUiState())
    val state: StateFlow<DuplicatesUiState> = _state.asStateFlow()

    override val status: ListStatus get() = _state.value.status

    private var moreJob: Job? = null

    init {
        // 只存组与翻页位置,勾选不存(恢复时回到默认勾选)
        cache.mirror(viewModelScope, CACHE_KEY, _state) { s -> DuplicatePage(s.groups, s.nextOffset).takeIf { s.status == ListStatus.Content } }
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    /** 从第一页重来,勾选回到默认(删过之后组都变了) */
    override suspend fun fetch(silent: Boolean) {
        moreJob?.cancel()
        // 上次看到的组先画出来,再静默从第一页重查
        val cached = if (silent) null else cache[CACHE_KEY]
        when {
            cached != null -> _state.update {
                it.copy(
                    status = ListStatus.Content,
                    groups = cached.items,
                    nextOffset = cached.nextOffset,
                    selection = SpaceLogic.defaultDuplicateSelection(cached.items),
                )
            }
            !silent -> _state.update { it.copy(status = ListStatus.Loading) }
        }
        _state.update { it.copy(loadingMore = false, loadMoreError = null) }
        gopanResult { repo.duplicates(offset = 0) }
            .onSuccess { page ->
                _state.update {
                    it.copy(
                        status = ListStatus.Content,
                        groups = page.items,
                        nextOffset = page.nextOffset,
                        selection = SpaceLogic.defaultDuplicateSelection(page.items),
                        refreshing = false,
                    )
                }
            }
            .onFailure { e ->
                if ((silent || cached != null) && _state.value.status == ListStatus.Content) {
                    _state.update { it.copy(refreshing = false) }
                    message(UiText.Plain(errorText(e)))
                } else {
                    _state.update { it.copy(status = ListStatus.Error(errorText(e)), refreshing = false, groups = emptyList(), selection = emptySet()) }
                }
            }
    }

    fun loadMore() {
        val s = _state.value
        val offset = s.nextOffset ?: return
        if (s.status != ListStatus.Content || s.loadingMore) return
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        moreJob = viewModelScope.launch {
            gopanResult { repo.duplicates(offset) }
                .onSuccess { page ->
                    _state.update {
                        val merged = SpaceLogic.mergeGroups(it.groups, page.items)
                        val added = merged.drop(it.groups.size)
                        it.copy(
                            groups = merged,
                            nextOffset = page.nextOffset,
                            loadingMore = false,
                            selection = it.selection + SpaceLogic.defaultDuplicateSelection(added),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, loadMoreError = e.message ?: "加载失败") } }
        }
    }

    fun toggle(id: String) {
        _state.update { s -> s.copy(selection = if (id in s.selection) s.selection - id else s.selection + id) }
    }

    /** 一个都不删 */
    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun requestDelete() {
        if (_state.value.selectedCount == 0) return
        _state.update { it.copy(confirmDelete = true) }
    }

    fun dismissDelete() {
        if (!_state.value.deleting) _state.update { it.copy(confirmDelete = false) }
    }

    fun confirmDelete() {
        val s = _state.value
        val ids = s.groups.flatMap { g -> g.files.map { it.node.id } }.filter { it in s.selection }.distinct()
        if (ids.isEmpty() || s.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            gopanResult { nodes.delete(ids) }
                .onSuccess {
                    _state.update { it.copy(deleting = false, confirmDelete = false) }
                    message(UiText.Res(R.string.space_msg_deleted, ids.size))
                    // 删掉之后有的组只剩一份、不再算重复:从头重查
                    load(silent = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(deleting = false) }
                    message(UiText.Plain(e.message ?: "操作失败"))
                }
        }
    }

    companion object {
        val CACHE_KEY = ScreenKey<DuplicatePage>("space/duplicates")
    }
}

// ---------- 分享与直链 ----------

/** 要吊销的一项:分享 / 直链 / 收集链接 */
@Immutable
sealed interface IdleRevoke {
    val id: String
    val name: String

    data class Share(override val id: String, override val name: String) : IdleRevoke

    data class Link(override val id: String, override val name: String) : IdleRevoke

    data class Request(override val id: String, override val name: String) : IdleRevoke
}

@Immutable
data class SpaceIdleUiState(
    val status: ListStatus = ListStatus.Loading,
    val idle: SpaceIdle? = null,
    val refreshing: Boolean = false,
    val confirm: IdleRevoke? = null,
    val revoking: Boolean = false,
)

/** 分享与直链:已过期的、或 90 天没人访问的分享与直链,已过期的收集链接;逐个吊销 */
@HiltViewModel
class SpaceIdleViewModel @Inject constructor(
    private val repo: SpaceRepository,
    nodes: NodesRepository,
    cache: ScreenCache,
) : SpaceTabViewModel(nodes, cache) {

    private val _state = MutableStateFlow(SpaceIdleUiState())
    val state: StateFlow<SpaceIdleUiState> = _state.asStateFlow()

    override val status: ListStatus get() = _state.value.status

    init {
        cache.mirror(viewModelScope, CACHE_KEY, _state) { s -> s.idle.takeIf { s.status == ListStatus.Content } }
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    override suspend fun fetch(silent: Boolean) {
        // 上次的清单先画出来,再静默重查
        val cached = if (silent) null else cache[CACHE_KEY]
        when {
            cached != null -> _state.update { it.copy(status = ListStatus.Content, idle = cached) }
            !silent -> _state.update { it.copy(status = ListStatus.Loading) }
        }
        gopanResult { repo.idle() }
            .onSuccess { idle -> _state.update { it.copy(status = ListStatus.Content, idle = idle, refreshing = false) } }
            .onFailure { e ->
                if ((silent || cached != null) && _state.value.idle != null) {
                    _state.update { it.copy(refreshing = false) }
                    message(UiText.Plain(errorText(e)))
                } else {
                    _state.update { it.copy(status = ListStatus.Error(errorText(e)), refreshing = false) }
                }
            }
    }

    fun requestRevoke(target: IdleRevoke) {
        if (!_state.value.revoking) _state.update { it.copy(confirm = target) }
    }

    fun dismissRevoke() {
        if (!_state.value.revoking) _state.update { it.copy(confirm = null) }
    }

    fun confirmRevoke() {
        val target = _state.value.confirm ?: return
        if (_state.value.revoking) return
        _state.update { it.copy(revoking = true) }
        viewModelScope.launch {
            gopanResult {
                when (target) {
                    is IdleRevoke.Share -> repo.revokeShare(target.id)
                    is IdleRevoke.Link -> repo.revokeLink(target.id)
                    is IdleRevoke.Request -> repo.revokeRequest(target.id)
                }
            }.onSuccess {
                _state.update { s ->
                    val idle = s.idle?.let { i ->
                        when (target) {
                            is IdleRevoke.Share -> i.copy(shares = i.shares.filterNot { it.id == target.id })
                            is IdleRevoke.Link -> i.copy(links = i.links.filterNot { it.id == target.id })
                            is IdleRevoke.Request -> i.copy(requests = i.requests.filterNot { it.id == target.id })
                        }
                    }
                    s.copy(revoking = false, confirm = null, idle = idle)
                }
                message(
                    UiText.Res(
                        when (target) {
                            is IdleRevoke.Share -> R.string.shares_msg_revoked
                            is IdleRevoke.Link -> R.string.links_msg_revoked
                            is IdleRevoke.Request -> R.string.collect_msg_revoked
                        },
                    ),
                )
            }.onFailure { e ->
                _state.update { it.copy(revoking = false) }
                message(UiText.Plain(e.message ?: "操作失败"))
            }
        }
    }

    companion object {
        val CACHE_KEY = ScreenKey<SpaceIdle>("space/idle")
    }
}
