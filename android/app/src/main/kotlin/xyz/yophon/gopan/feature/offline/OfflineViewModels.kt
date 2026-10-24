package xyz.yophon.gopan.feature.offline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.offline.OfflineFileEntity
import xyz.yophon.gopan.core.offline.OfflineItem
import xyz.yophon.gopan.core.offline.OfflineRepository
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.preview.PreviewItem
import xyz.yophon.gopan.feature.preview.PreviewSessionStore
import javax.inject.Inject

sealed interface OfflineEffect {
    data class Message(val text: UiText) : OfflineEffect

    /** 预览:翻页列表已放进 PreviewSessionStore */
    data class OpenPreview(val nodeId: String) : OfflineEffect

    data class OpenFolder(val route: Routes.OfflineFolder) : OfflineEffect
}

/** 「离线可用」页(doc/26):加过的文件与文件夹,打开、更新、移除 */
@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val offline: OfflineRepository,
    private val previews: PreviewSessionStore,
) : ViewModel() {
    /** null = 还在读 */
    val items: StateFlow<List<OfflineItem>?> = offline.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _removing = MutableStateFlow<OfflineItem?>(null)

    /** 正在确认移除的那一项 */
    val removing: StateFlow<OfflineItem?> = _removing.asStateFlow()

    private val _effects = Channel<OfflineEffect>(Channel.BUFFERED)
    val effects: Flow<OfflineEffect> = _effects.receiveAsFlow()

    /** 文件夹进去按目录看;文件直接预览(有副本时预览用手机上的那份) */
    fun open(item: OfflineItem) {
        if (item.isFolder) {
            _effects.trySend(OfflineEffect.OpenFolder(Routes.OfflineFolder(rootId = item.nodeId, title = item.name)))
        } else {
            previews.openItems(listOf(PreviewItem(item.nodeId, item.name, item.cloudBytes)))
            _effects.trySend(OfflineEffect.OpenPreview(item.nodeId))
        }
    }

    fun update(item: OfflineItem) {
        viewModelScope.launch {
            offline.update(item.nodeId)
            _effects.trySend(OfflineEffect.Message(UiText.Res(R.string.offline_msg_updating, item.name)))
        }
    }

    fun requestRemove(item: OfflineItem) {
        _removing.value = item
    }

    fun dismissRemove() {
        _removing.value = null
    }

    fun confirmRemove() {
        val item = _removing.value ?: return
        _removing.value = null
        viewModelScope.launch {
            offline.unpin(listOf(item.nodeId))
            _effects.trySend(OfflineEffect.Message(UiText.Res(R.string.offline_msg_removed)))
        }
    }
}

/** 离线文件夹项里的一层目录:子目录与文件,都来自手机上的登记,没网也能看 */
@HiltViewModel
class OfflineFolderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    offline: OfflineRepository,
    private val previews: PreviewSessionStore,
) : ViewModel() {
    private val rootId: String = requireNotNull(savedStateHandle["rootId"]) { "缺少 rootId" }
    private val path: String = savedStateHandle["path"] ?: ""
    private val rootTitle: String = savedStateHandle["title"] ?: ""

    private val files = offline.filesOf(rootId)

    /** null = 还在读 */
    val listing: StateFlow<OfflineFolderListing?> = files
        .map { listOfflineFolder(it, path) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 顶栏标题:子目录显示目录名,文件夹项显示它的名字;从文件页没网时跳过来不带名字,
     * 用登记里相对目录的第一段(就是文件夹项自己的名字)
     */
    val title: StateFlow<String> = when {
        path.isNotEmpty() -> MutableStateFlow(path.substringAfterLast('/'))
        rootTitle.isNotEmpty() -> MutableStateFlow(rootTitle)
        else -> files.map { list -> list.firstOrNull()?.relDir?.substringBefore('/').orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    }

    private val _effects = Channel<OfflineEffect>(Channel.BUFFERED)
    val effects: Flow<OfflineEffect> = _effects.receiveAsFlow()

    fun openFolder(folder: OfflineSubfolder) {
        _effects.trySend(OfflineEffect.OpenFolder(Routes.OfflineFolder(rootId = rootId, path = folder.path, title = rootTitle)))
    }

    /** 预览这一层的文件,左右翻页在这一层里翻 */
    fun openFile(file: OfflineFileEntity) {
        val files = listing.value?.files ?: listOf(file)
        previews.openItems(files.map { PreviewItem(it.nodeId, it.name, it.size) })
        _effects.trySend(OfflineEffect.OpenPreview(file.nodeId))
    }
}
