package xyz.yophon.gopan.feature.media

import androidx.compose.runtime.Immutable
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
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.media.MediaRepository
import xyz.yophon.gopan.core.data.media.RecentPlay
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.preview.PreviewSessionStore
import javax.inject.Inject

@Immutable
data class RecentPlaysUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<RecentPlay> = emptyList(),
    val isRefreshing: Boolean = false,
    /** 相对时间(「3 小时前」)的基准,每次加载时更新 */
    val nowMillis: Long = System.currentTimeMillis(),
)

sealed interface RecentPlaysEffect {
    data class OpenPreview(val nodeId: String) : RecentPlaysEffect

    data class Message(val text: UiText) : RecentPlaysEffect
}

/**
 * 最近播放(doc/22 二.3):没看完的音视频与 PDF,最近的在前(最多 20 条)。
 * 点一条打开预览(接着上次的位置);「移出」清掉这条进度。从预览回来时静默刷新(进度变了、看完了的会消失)。
 */
@HiltViewModel
class RecentPlaysViewModel @Inject constructor(
    private val media: MediaRepository,
    private val sessions: PreviewSessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(RecentPlaysUiState())
    val state: StateFlow<RecentPlaysUiState> = _state.asStateFlow()

    private val _effects = Channel<RecentPlaysEffect>(Channel.BUFFERED)
    val effects: Flow<RecentPlaysEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var resumeJob: Job? = null
    private var resumedOnce = false

    init {
        load(LoadMode.INITIAL)
    }

    private enum class LoadMode { INITIAL, REFRESH, SILENT }

    fun retry() = load(LoadMode.INITIAL)

    fun refresh() = load(LoadMode.REFRESH)

    /**
     * 第一次 RESUME 是刚打开(init 已经在加载);之后是从预览回来:等一下再刷新,
     * 让离开预览时报的进度先到服务端
     */
    fun onResumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        if (_state.value.status != ListStatus.Content) return
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            delay(REFRESH_AFTER_PREVIEW_MS)
            load(LoadMode.SILENT)
        }
    }

    private fun load(mode: LoadMode) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    status = if (mode == LoadMode.INITIAL) ListStatus.Loading else it.status,
                    isRefreshing = mode == LoadMode.REFRESH,
                )
            }
            gopanResult { media.recentPlays() }
                .onSuccess { items ->
                    _state.update {
                        it.copy(items = items, status = ListStatus.Content, isRefreshing = false, nowMillis = System.currentTimeMillis())
                    }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    val hadItems = _state.value.items.isNotEmpty()
                    _state.update {
                        if (mode == LoadMode.INITIAL || !hadItems) {
                            it.copy(status = ListStatus.Error(message), isRefreshing = false, items = emptyList())
                        } else {
                            it.copy(isRefreshing = false)
                        }
                    }
                    if (mode == LoadMode.REFRESH && hadItems) send(RecentPlaysEffect.Message(UiText.Plain(message)))
                }
        }
    }

    /** 打开预览:只放这一个(视频放完会按同文件夹的列表接下一集,音频会把整个文件夹排进队列) */
    fun open(play: RecentPlay) {
        sessions.open(listOf(play.node))
        send(RecentPlaysEffect.OpenPreview(play.node.id))
    }

    /** 移出最近播放:先从列表拿掉,失败了放回原处 */
    fun remove(play: RecentPlay) {
        val before = _state.value.items
        val index = before.indexOfFirst { it.node.id == play.node.id }
        if (index < 0) return
        _state.update { s -> s.copy(items = s.items.filterNot { it.node.id == play.node.id }) }
        viewModelScope.launch {
            gopanResult { media.clearProgress(play.node.id) }
                .onSuccess { send(RecentPlaysEffect.Message(UiText.Res(R.string.media_recent_removed))) }
                .onFailure { e ->
                    _state.update { s ->
                        if (s.items.any { it.node.id == play.node.id }) {
                            s
                        } else {
                            s.copy(items = s.items.toMutableList().apply { add(index.coerceAtMost(size), play) })
                        }
                    }
                    send(RecentPlaysEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }

    private fun send(effect: RecentPlaysEffect) {
        _effects.trySend(effect)
    }

    private companion object {
        const val REFRESH_AFTER_PREVIEW_MS = 1_000L
    }
}
