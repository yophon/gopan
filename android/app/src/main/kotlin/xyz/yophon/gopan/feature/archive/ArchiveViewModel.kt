package xyz.yophon.gopan.feature.archive

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
import xyz.yophon.gopan.core.data.archive.ArchiveEntry
import xyz.yophon.gopan.core.data.archive.ArchiveInfo
import xyz.yophon.gopan.core.data.archive.ArchiveRepository
import xyz.yophon.gopan.core.data.archive.ArchiveStatus
import xyz.yophon.gopan.core.data.archive.EntryKind
import xyz.yophon.gopan.core.data.archive.ExtractRequest
import xyz.yophon.gopan.core.data.archive.archiveParentPath
import xyz.yophon.gopan.core.data.archive.entryKind
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.share.AppNavRequests
import xyz.yophon.gopan.core.share.NavRequest
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.preview.PreviewContent
import java.io.File
import javax.inject.Inject

/** 压缩包概要的加载状态 */
sealed interface ArchiveInfoState {
    data object Loading : ArchiveInfoState

    /** 服务端还在读目录:每 2 秒问一次 */
    data object Pending : ArchiveInfoState

    data class Ready(val info: ArchiveInfo) : ArchiveInfoState

    /** 打不开(格式不支持、读取失败);[message] 服务端给的原因 */
    data class Unavailable(val message: String?) : ArchiveInfoState

    /** 请求失败(网络等),可重试 */
    data class Error(val message: String) : ArchiveInfoState
}

/** 包里一个文件的查看器 */
sealed interface EntryViewer {
    val entry: ArchiveEntry

    /** 正在从服务端取;[fraction] 总大小未知时为 null */
    data class Loading(override val entry: ArchiveEntry, val fraction: Float?) : EntryViewer

    data class Failed(override val entry: ArchiveEntry, val message: String) : EntryViewer

    data class Image(override val entry: ArchiveEntry, val file: File) : EntryViewer

    data class Pdf(override val entry: ArchiveEntry, val file: File) : EntryViewer

    /** 文本分段读(和网盘文本预览同一个组件) */
    data class Text(override val entry: ArchiveEntry, val file: File, val text: PreviewContent.Text) : EntryViewer
}

/** 已经取到本地的文件(查看器里「用其他应用打开」「分享」直接用) */
val EntryViewer.file: File?
    get() = when (this) {
        is EntryViewer.Image -> file
        is EntryViewer.Pdf -> file
        is EntryViewer.Text -> file
        is EntryViewer.Loading, is EntryViewer.Failed -> null
    }

/** 不能预览的文件正在取到本地,取完交给其他应用([share]:分享,否则打开) */
@Immutable
data class EntryFetch(val entry: ArchiveEntry, val share: Boolean, val fraction: Float? = null)

@Immutable
data class ArchiveUiState(
    val name: String,
    val info: ArchiveInfoState = ArchiveInfoState.Loading,
    /** 包内当前目录,"" = 根 */
    val dir: String = "",
    val entries: List<ArchiveEntry> = emptyList(),
    val total: Int = 0,
    val nextCursor: String? = null,
    val listStatus: ListStatus = ListStatus.Loading,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    /** 勾选的条目(包内完整路径,按勾选顺序) */
    val selection: Set<String> = emptySet(),
    val viewer: EntryViewer? = null,
    /** 点了一个不能预览、但能单独取出来的文件:选「用其他应用打开」还是「分享」 */
    val fileMenu: ArchiveEntry? = null,
    val fetching: EntryFetch? = null,
) {
    val crumbs: List<ArchiveCrumb> get() = archiveCrumbs(dir)
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val canGoUp: Boolean get() = dir.isNotEmpty()
    val extractLabel: ExtractLabel get() = extractLabel(dir, selection.size)
    val readyInfo: ArchiveInfo? get() = (info as? ArchiveInfoState.Ready)?.info

    /** 当前已加载的条目里能勾的都勾上了 */
    val allSelected: Boolean
        get() = entries.filter { it.selectable }.let { list -> list.isNotEmpty() && list.all { it.path in selection } }
}

sealed interface ArchiveEffect {
    data class Message(val text: UiText) : ArchiveEffect

    /** 解压任务已提交:Snackbar 带「查看」 */
    data object ExtractStarted : ArchiveEffect

    /** 取到本地的文件交给其他应用 */
    data class LaunchFile(val file: File, val share: Boolean) : ArchiveEffect
}

/**
 * 压缩包浏览页:一次一个目录(目录在前,200 条一页,滚到底接着拉);系统返回先回上一级目录。
 * 长按进入多选(带标记的条目不能勾),解压的范围是当前目录:没勾就是整个目录,勾了就是勾的那些。
 * 点可预览的文件取到缓存后在页内查看;别的文件取下来交给其他应用。离开页面时删掉取到缓存的文件。
 */
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val archives: ArchiveRepository,
    private val navRequests: AppNavRequests,
) : ViewModel() {

    private val handle = savedStateHandle
    val nodeId: String = requireNotNull(savedStateHandle.get<String>(NODE_ID_ARG)) { "缺少 nodeId" }

    /** 压缩包所在的文件夹:「解压到这里」的目标;null = 根目录 */
    private val archiveParentId: String? = savedStateHandle.get<String>(PARENT_ID_ARG)

    private val _state = MutableStateFlow(
        ArchiveUiState(
            name = savedStateHandle.get<String>(NAME_ARG).orEmpty(),
            // 进程重建后回到当时所在的目录
            dir = savedStateHandle.get<String>(DIR_KEY).orEmpty(),
        ),
    )
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    private val _effects = Channel<ArchiveEffect>(Channel.BUFFERED)
    val effects: Flow<ArchiveEffect> = _effects.receiveAsFlow()

    val extract = ExtractSubmitter(archives, viewModelScope) { event ->
        when (event) {
            is ExtractEvent.Started -> {
                clearSelection()
                send(ArchiveEffect.ExtractStarted)
            }
            is ExtractEvent.Failed -> send(ArchiveEffect.Message(UiText.Plain(event.message)))
        }
    }

    private class Listing(val entries: List<ArchiveEntry>, val nextCursor: String?, val total: Int)

    /** 看过的目录(按最近使用):回上一级时直接显示,不再请求 */
    private val listings = object : LinkedHashMap<String, Listing>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listing>?): Boolean = size > MAX_CACHED_DIRS
    }

    private var infoJob: Job? = null
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var viewerJob: Job? = null
    private var fetchJob: Job? = null

    init {
        loadInfo()
    }

    // ---- 概要 ----

    private fun loadInfo() {
        infoJob?.cancel()
        _state.update { it.copy(info = ArchiveInfoState.Loading) }
        infoJob = viewModelScope.launch {
            var first = true
            while (true) {
                val result = gopanResult { archives.info(nodeId) }
                val info = result.getOrNull()
                if (info == null) {
                    // 第一次就失败给重试;轮询途中的偶发失败下一轮再问
                    if (first) {
                        _state.update { it.copy(info = ArchiveInfoState.Error(result.exceptionOrNull()?.message ?: "读取压缩包失败")) }
                        return@launch
                    }
                } else {
                    first = false
                    when {
                        info.status == ArchiveStatus.PENDING -> _state.update { it.copy(info = ArchiveInfoState.Pending) }
                        info.ready -> {
                            _state.update { it.copy(info = ArchiveInfoState.Ready(info)) }
                            openDir(_state.value.dir)
                            return@launch
                        }
                        else -> {
                            _state.update { it.copy(info = ArchiveInfoState.Unavailable(info.error?.takeIf { e -> e.isNotBlank() })) }
                            return@launch
                        }
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    /** 概要或当前目录加载失败后重试 */
    fun retry() {
        val s = _state.value
        when {
            s.info is ArchiveInfoState.Error -> loadInfo()
            s.listStatus is ListStatus.Error -> fetchFirstPage(s.dir)
        }
    }

    // ---- 目录 ----

    /** 进入包内某个目录("" = 根);勾选随之清空 */
    fun openDir(path: String) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        handle[DIR_KEY] = path
        val cached = listings[path]
        _state.update {
            it.copy(
                dir = path,
                selection = emptySet(),
                entries = cached?.entries ?: emptyList(),
                total = cached?.total ?: 0,
                nextCursor = cached?.nextCursor,
                listStatus = if (cached != null) ListStatus.Content else ListStatus.Loading,
                isLoadingMore = false,
                loadMoreError = null,
            )
        }
        if (cached == null) fetchFirstPage(path)
    }

    private fun fetchFirstPage(path: String) {
        loadJob?.cancel()
        _state.update { if (it.dir == path) it.copy(listStatus = ListStatus.Loading) else it }
        loadJob = viewModelScope.launch {
            gopanResult { archives.entries(nodeId, path, null) }
                .onSuccess { page ->
                    listings[path] = Listing(page.items, page.nextCursor, page.total)
                    _state.update {
                        if (it.dir != path) it
                        else it.copy(entries = page.items, total = page.total, nextCursor = page.nextCursor, listStatus = ListStatus.Content)
                    }
                }
                .onFailure { e ->
                    _state.update { if (it.dir != path) it else it.copy(listStatus = ListStatus.Error(e.message ?: "加载失败")) }
                }
        }
    }

    /** 滚到接近末尾:拉下一页 */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.isLoadingMore || s.listStatus != ListStatus.Content) return
        val path = s.dir
        _state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        loadMoreJob = viewModelScope.launch {
            gopanResult { archives.entries(nodeId, path, cursor) }
                .onSuccess { page ->
                    val current = _state.value
                    if (current.dir != path) return@onSuccess
                    val merged = (current.entries + page.items).distinctBy { it.idx }
                    listings[path] = Listing(merged, page.nextCursor, page.total)
                    _state.update { it.copy(entries = merged, nextCursor = page.nextCursor, total = page.total, isLoadingMore = false) }
                }
                .onFailure { e ->
                    _state.update { if (it.dir != path) it else it.copy(isLoadingMore = false, loadMoreError = e.message ?: "加载失败") }
                }
        }
    }

    /** 回上一级;已经在根目录(或目录还没读出来)返回 false */
    fun up(): Boolean {
        val dir = _state.value.dir
        if (dir.isEmpty() || _state.value.readyInfo == null) return false
        openDir(archiveParentPath(dir))
        return true
    }

    /** 系统返回:先关查看器、再退出多选、再回上一级;都没有(该离开页面了)返回 false */
    fun onBack(): Boolean {
        val s = _state.value
        return when {
            s.viewer != null -> {
                closeViewer()
                true
            }
            s.selectionMode -> {
                clearSelection()
                true
            }
            else -> up()
        }
    }

    // ---- 点击 / 多选 ----

    fun onEntryClick(entry: ArchiveEntry) {
        if (_state.value.selectionMode) {
            if (entry.selectable) toggle(entry) else entry.blockMessage()?.let { send(ArchiveEffect.Message(it)) }
            return
        }
        when (entry.tapAction()) {
            EntryTap.OPEN_DIR -> openDir(entry.path)
            EntryTap.PREVIEW -> openViewer(entry)
            EntryTap.FILE_ACTIONS -> _state.update { it.copy(fileMenu = entry) }
            EntryTap.BLOCKED -> entry.blockMessage()?.let { send(ArchiveEffect.Message(it)) }
        }
    }

    /** 长按:勾选(进入多选);带标记的条目解压时会被跳过,不能勾 */
    fun onEntryLongClick(entry: ArchiveEntry) {
        if (!entry.selectable) {
            entry.blockMessage()?.let { send(ArchiveEffect.Message(it)) }
            return
        }
        toggle(entry)
    }

    fun toggle(entry: ArchiveEntry) {
        if (!entry.selectable) return
        _state.update { s -> s.copy(selection = if (entry.path in s.selection) s.selection - entry.path else s.selection + entry.path) }
    }

    fun selectAll() {
        _state.update { s -> s.copy(selection = s.selection + s.entries.filter { it.selectable }.map { it.path }) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    // ---- 解压 ----

    /**
     * 现在点「解压」会提交什么:base = 当前目录(解出来的路径相对它),paths = 勾选的完整路径(没勾 = 当前目录下全部),
     * 默认解到压缩包所在的文件夹。
     */
    fun extractRequest(parentId: String? = archiveParentId): ExtractRequest {
        val s = _state.value
        return ExtractRequest(nodeId = nodeId, base = s.dir.ifEmpty { null }, paths = s.selection.toList(), parentId = parentId)
    }

    /** 主按钮:解到压缩包旁边 */
    fun extractHere() = extract.submit(extractRequest())

    /** 「解压到…」:先选目标文件夹 */
    fun extractTo() = extract.pick(extractRequest())

    /** Snackbar 的「查看」:请主界面打开传输列表(界面随后退回主界面) */
    fun showTransfers() = navRequests.post(NavRequest.OPEN_TRANSFERS)

    // ---- 包里的单个文件 ----

    fun openViewer(entry: ArchiveEntry) {
        viewerJob?.cancel()
        _state.update { it.copy(viewer = EntryViewer.Loading(entry, null)) }
        viewerJob = viewModelScope.launch {
            var lastPercent = -1
            gopanResult {
                archives.fetchEntry(nodeId, entry) { done, total ->
                    if (total != null && total > 0) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            setViewer(entry) { EntryViewer.Loading(entry, percent / 100f) }
                        }
                    }
                }
            }.fold(
                onSuccess = { file ->
                    val viewer = when (entryKind(entry.name)) {
                        EntryKind.IMAGE -> EntryViewer.Image(entry, file)
                        EntryKind.PDF -> EntryViewer.Pdf(entry, file)
                        EntryKind.TEXT -> gopanResult { archives.readText(file) }.fold(
                            onSuccess = { EntryViewer.Text(entry, file, PreviewContent.Text(it)) },
                            onFailure = { EntryViewer.Failed(entry, it.message ?: "读取失败") },
                        )
                    }
                    setViewer(entry) { viewer }
                },
                onFailure = { e -> setViewer(entry) { EntryViewer.Failed(entry, e.message ?: "加载失败") } },
            )
        }
    }

    fun retryViewer() {
        _state.value.viewer?.let { openViewer(it.entry) }
    }

    fun closeViewer() {
        viewerJob?.cancel()
        _state.update { it.copy(viewer = null) }
    }

    /** 文本:接着读下一段 */
    fun loadMoreText() {
        val viewer = _state.value.viewer as? EntryViewer.Text ?: return
        val content = viewer.text.content
        if (!content.truncated || viewer.text.loadingMore) return
        val entry = viewer.entry
        setViewer(entry) { viewer.copy(text = viewer.text.copy(loadingMore = true, moreError = null)) }
        viewModelScope.launch {
            gopanResult { archives.readText(viewer.file, content.nextOffset, content.charset) }
                .onSuccess { next ->
                    val merged = content.copy(
                        text = content.text + next.text,
                        truncated = next.truncated,
                        nextOffset = next.nextOffset,
                        totalBytes = next.totalBytes ?: content.totalBytes,
                    )
                    setViewer(entry) { viewer.copy(text = PreviewContent.Text(merged)) }
                }
                .onFailure { e ->
                    setViewer(entry) { viewer.copy(text = viewer.text.copy(loadingMore = false, moreError = e.message ?: "加载失败")) }
                }
        }
    }

    /** 查看器里的「用其他应用打开」「分享」:文件已经在本地 */
    fun launchViewerFile(share: Boolean) {
        val file = _state.value.viewer?.file ?: return
        send(ArchiveEffect.LaunchFile(file, share))
    }

    fun dismissFileMenu() {
        _state.update { it.copy(fileMenu = null) }
    }

    /** 不能预览的文件:取到本地后交给其他应用 */
    fun fetchForApp(entry: ArchiveEntry, share: Boolean) {
        fetchJob?.cancel()
        _state.update { it.copy(fileMenu = null, fetching = EntryFetch(entry, share)) }
        fetchJob = viewModelScope.launch {
            var lastPercent = -1
            gopanResult {
                archives.fetchEntry(nodeId, entry) { done, total ->
                    if (total != null && total > 0) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.update { s ->
                                val f = s.fetching
                                if (f != null && f.entry.idx == entry.idx) s.copy(fetching = f.copy(fraction = percent / 100f)) else s
                            }
                        }
                    }
                }
            }.onSuccess { file ->
                _state.update { it.copy(fetching = null) }
                send(ArchiveEffect.LaunchFile(file, share))
            }.onFailure { e ->
                _state.update { it.copy(fetching = null) }
                send(ArchiveEffect.Message(UiText.Plain(e.message ?: "读取失败")))
            }
        }
    }

    fun cancelFetch() {
        fetchJob?.cancel()
        _state.update { it.copy(fetching = null) }
    }

    fun message(text: UiText) = send(ArchiveEffect.Message(text))

    private inline fun setViewer(entry: ArchiveEntry, crossinline next: () -> EntryViewer) {
        _state.update { s -> if (s.viewer?.entry?.idx == entry.idx) s.copy(viewer = next()) else s }
    }

    private fun send(effect: ArchiveEffect) {
        _effects.trySend(effect)
    }

    override fun onCleared() {
        archives.release(nodeId)
    }

    companion object {
        /** 与 Routes.Archive 的属性名一致 */
        const val NODE_ID_ARG = "nodeId"
        const val NAME_ARG = "name"
        const val PARENT_ID_ARG = "parentId"
        private const val DIR_KEY = "archive.dir"
        const val POLL_MS = 2_000L
        private const val MAX_CACHED_DIRS = 32
    }
}
