package xyz.yophon.gopan.feature.drive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.mergePages
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import javax.inject.Inject

@Immutable
data class FolderPickerUiState(
    /** 根在前;最后一项是当前所在目录 */
    val path: List<Crumb> = listOf(Crumb(null, "")),
    /** 当前目录下的子文件夹(已排除被移动的节点) */
    val folders: List<Node> = emptyList(),
    val status: ListStatus = ListStatus.Loading,
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
) {
    val current: Crumb get() = path.last()
    val canGoUp: Boolean get() = path.size > 1
}

/**
 * 移动 / 复制的目标目录选择器:从根开始逐级进入。被操作的节点本身不显示(其子树自然不可达),
 * 更深的环移动由服务端 INVALID_INPUT 兜底。同一个对话框实例(token)内保留浏览位置,再次打开回到根。
 */
@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    private val repo: NodesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FolderPickerUiState())
    val state: StateFlow<FolderPickerUiState> = _state.asStateFlow()

    private var token: Long? = null
    private var excludeIds: Set<String> = emptySet()
    private var loadJob: Job? = null

    /** 对话框打开时调用;同一 token 重复调用(旋转屏幕)不重置 */
    fun open(token: Long, excludeIds: Collection<String>) {
        if (this.token == token) return
        this.token = token
        this.excludeIds = excludeIds.toSet()
        _state.value = FolderPickerUiState()
        loadCurrent()
    }

    fun enter(folder: Node) {
        _state.update { it.copy(path = it.path + Crumb(folder.id, folder.name)) }
        loadCurrent()
    }

    fun up() {
        if (!_state.value.canGoUp) return
        _state.update { it.copy(path = it.path.dropLast(1)) }
        loadCurrent()
    }

    /** 点面包屑:回到路径里的某一级 */
    fun jumpTo(index: Int) {
        val path = _state.value.path
        if (index !in path.indices || index == path.lastIndex) return
        _state.update { it.copy(path = path.subList(0, index + 1)) }
        loadCurrent()
    }

    fun retry() = loadCurrent()

    private fun loadCurrent() {
        loadJob?.cancel()
        val parentId = _state.value.current.id
        loadJob = viewModelScope.launch {
            _state.update { it.copy(status = ListStatus.Loading, folders = emptyList(), nextCursor = null, isLoadingMore = false) }
            gopanResult { repo.children(parentId, null, DriveSortOrder.NAME, false) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(status = ListStatus.Content, folders = page.items.pickFolders(), nextCursor = page.nextCursor)
                    }
                }
                .onFailure { e ->
                    val ge = e as? GopanException ?: e.toGopanException()
                    _state.update { it.copy(status = ListStatus.Error(ge.message ?: ge.code)) }
                }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.isLoadingMore || s.status != ListStatus.Content) return
        val parentId = s.current.id
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            gopanResult { repo.children(parentId, cursor, DriveSortOrder.NAME, false) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(folders = mergePages(it.folders, page.items.pickFolders()), nextCursor = page.nextCursor, isLoadingMore = false)
                    }
                }
                .onFailure { _state.update { it.copy(isLoadingMore = false) } }
        }
    }

    private fun List<Node>.pickFolders(): List<Node> = filter { it.isFolder && it.id !in excludeIds }
}
