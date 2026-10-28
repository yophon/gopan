package xyz.yophon.gopan.feature.shares

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.serialization.builtins.ListSerializer
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.shares.Share
import xyz.yophon.gopan.core.data.shares.ShareVisit
import xyz.yophon.gopan.core.data.shares.SharesRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject

@Immutable
data class SharesUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<Share> = emptyList(),
    val refreshing: Boolean = false,
    /** 正在取消的分享 id(按钮转圈、防连点) */
    val revoking: Set<String> = emptySet(),
)

sealed interface SharesEffect {
    data class Message(val text: UiText) : SharesEffect
}

/** 「分享」tab:我的分享列表;复制 / 发送 / 打开链接由界面交给系统,取消分享在这里 */
@HiltViewModel
class SharesViewModel @Inject constructor(
    private val repo: SharesRepository,
    private val config: AppConfig,
    cache: ScreenCache,
) : ViewModel() {

    private val _state = MutableStateFlow(SharesUiState())
    val state: StateFlow<SharesUiState> = _state.asStateFlow()

    private val _effects = Channel<SharesEffect>(Channel.BUFFERED)
    val effects: Flow<SharesEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        // 上次的列表(或启动时预取的)先画出来,再静默刷新
        val cached = cache[CACHE_KEY]
        if (cached != null) _state.update { it.copy(status = ListStatus.Content, items = cached) }
        cache.mirror(viewModelScope, CACHE_KEY, _state) { s -> s.items.takeIf { s.status == ListStatus.Content } }
        load(silent = cached != null)
        // 冷启动后内存里还没有:手机上存的上次列表读回来先画,联网结果到了再换
        if (cached == null) {
            viewModelScope.launch {
                cache.load(CACHE_KEY)?.let { items ->
                    _state.update { s -> if (!loadedOnce && s.status == ListStatus.Loading) s.copy(status = ListStatus.Content, items = items) else s }
                }
            }
        }
    }

    fun url(share: Share): String = config.shareUrl(share.token)

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    /** 回到这一页:静默刷新(访问计数会变,别处可能新建了分享) */
    fun onResumed() {
        if (loadedOnce && loadJob?.isActive != true) load(silent = true)
    }

    fun retry() = load(silent = false)

    private fun load(silent: Boolean) {
        loadJob?.cancel()
        if (!silent) _state.update { it.copy(status = ListStatus.Loading) }
        loadJob = viewModelScope.launch {
            gopanResult { repo.myShares() }
                .onSuccess { items ->
                    loadedOnce = true
                    _state.update { it.copy(status = ListStatus.Content, items = sortShares(items), refreshing = false) }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    // 手上已有列表:保留,只提示;否则显示错误页
                    val keep = _state.value.status == ListStatus.Content && _state.value.items.isNotEmpty()
                    if (keep) {
                        _state.update { it.copy(refreshing = false) }
                        _effects.trySend(SharesEffect.Message(UiText.Plain(message)))
                    } else {
                        _state.update { it.copy(status = ListStatus.Error(message), refreshing = false) }
                    }
                }
        }
    }

    fun revoke(share: Share) {
        if (share.id in _state.value.revoking) return
        _state.update { it.copy(revoking = it.revoking + share.id) }
        viewModelScope.launch {
            val result = gopanResult { repo.revoke(share.id) }
            // 已经不存在(别处取消过)也当成功
            val ok = result.isSuccess || (result.exceptionOrNull() as? GopanException)?.code == GopanErrors.NOT_FOUND
            _state.update {
                it.copy(
                    revoking = it.revoking - share.id,
                    items = if (ok) it.items.filterNot { s -> s.id == share.id } else it.items,
                )
            }
            _effects.trySend(
                SharesEffect.Message(
                    if (ok) UiText.Res(R.string.shares_msg_revoked) else UiText.Plain(result.exceptionOrNull()?.message ?: "取消分享失败"),
                ),
            )
        }
    }

    /** 文件夹分享打开 / 关掉「对方上传」(doc/32 一) */
    fun toggleUpload(share: Share) {
        val allow = !share.allowUpload
        viewModelScope.launch {
            gopanResult { repo.setUpload(share.id, allow) }
                .onSuccess {
                    _state.update { s -> s.copy(items = s.items.map { if (it.id == share.id) it.copy(allowUpload = allow) else it }) }
                    _effects.trySend(SharesEffect.Message(UiText.Res(if (allow) R.string.shares_msg_upload_on else R.string.shares_msg_upload_off)))
                }
                .onFailure { e -> _effects.trySend(SharesEffect.Message(UiText.Plain(e.message ?: "设置失败"))) }
        }
    }

    companion object {
        /** 落盘,冷启动后也有 */
        val CACHE_KEY = ScreenKey("shares", ListSerializer(Share.serializer()))

        /** 新建的在前(与网页端一致:服务端按创建时间倒序,这里再保证一次) */
        fun sortShares(items: List<Share>): List<Share> = items.sortedByDescending { it.createdAt }
    }
}

@Immutable
data class VisitsUiState(val status: ListStatus = ListStatus.Loading, val items: List<ShareVisit> = emptyList())

/** 一个分享的访问明细(最近 50 条) */
@HiltViewModel
class ShareVisitsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SharesRepository,
    private val cache: ScreenCache,
) : ViewModel() {
    val shareId: String = requireNotNull(savedStateHandle.get<String>("shareId"))
    val shareName: String = savedStateHandle.get<String>("name").orEmpty()

    private val _state = MutableStateFlow(VisitsUiState())
    val state: StateFlow<VisitsUiState> = _state.asStateFlow()

    private val cacheKey = ScreenKey<List<ShareVisit>>("share-visits/$shareId")

    init {
        load()
    }

    /** 看过的明细先画出来再刷新;刷新失败而手上有数据时保留数据 */
    fun load() {
        val cached = cache[cacheKey]
        _state.update { if (cached != null) it.copy(status = ListStatus.Content, items = cached) else it.copy(status = ListStatus.Loading) }
        viewModelScope.launch {
            gopanResult { repo.visits(shareId) }
                .onSuccess { items ->
                    cache.put(cacheKey, items)
                    _state.update { it.copy(status = ListStatus.Content, items = items) }
                }
                .onFailure { e -> if (cached == null) _state.update { it.copy(status = ListStatus.Error(e.message ?: "加载失败")) } }
        }
    }
}
