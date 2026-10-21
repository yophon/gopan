package xyz.yophon.gopan.core.data.media

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 播放队列里的一首:mediaId 即节点 id,[title] 是文件名 */
@Immutable
data class QueueEntry(val nodeId: String, val title: String)

/** 后台播放服务正在放的(迷你播放条、预览页跟随翻页用) */
@Immutable
data class NowPlaying(
    val nodeId: String,
    val title: String,
    val isPlaying: Boolean,
    val hasNext: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** 整个队列,按列表顺序(不是随机播放的顺序) */
    val queue: List<QueueEntry>,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 进程内的「正在播放」:由 PlaybackService 写(状态变化时、播放中每秒),界面只读。
 * 服务没起来时是 null —— 迷你播放条据此决定显不显示,不必为了看一眼就连上(拉起)服务。
 */
@Singleton
class NowPlayingStore @Inject constructor() {
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val _stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 登出:请服务停播并清空队列(上一个账号的队列不能留给下一个) */
    val stopRequests: SharedFlow<Unit> = _stops.asSharedFlow()

    fun update(value: NowPlaying?) {
        _state.value = value
    }

    fun requestStop() {
        _state.value = null
        _stops.tryEmit(Unit)
    }
}
