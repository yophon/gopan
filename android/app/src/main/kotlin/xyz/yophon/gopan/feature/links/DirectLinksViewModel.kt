package xyz.yophon.gopan.feature.links

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
import xyz.yophon.gopan.core.data.links.DirectLink
import xyz.yophon.gopan.core.data.links.DirectLinksRepository
import xyz.yophon.gopan.core.data.links.LinkStatus
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.shares.SharesTabRequest
import xyz.yophon.gopan.feature.shares.SharesTabTarget
import java.time.Instant
import javax.inject.Inject

@Immutable
data class DirectLinksUiState(
    /** 本站开了直链;null = 还没问到(「直链」标签先不出现) */
    val enabled: Boolean? = null,
    val status: ListStatus = ListStatus.Loading,
    val items: List<DirectLink> = emptyList(),
    val total: Int = 0,
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    /** 正在暂停 / 恢复 / 吊销的直链 id(按钮转圈、防连点) */
    val busy: Set<String> = emptySet(),
)

sealed interface DirectLinksEffect {
    data class Message(val text: UiText) : DirectLinksEffect

    /** 打开这一条的操作表(通知点进来的) */
    data class Focus(val linkId: String) : DirectLinksEffect
}

/**
 * 「分享」tab 的「直链」标签(doc/22 四.4):我的直链、今天与近 30 天的次数和流量、状态;复制、暂停 / 恢复、吊销。
 * 图表、来源、明细与各项设置在网页上。第一次切到这个标签时才拉列表;也负责「分享」tab 的标签切换请求(通知点进来)。
 */
@HiltViewModel
class DirectLinksViewModel @Inject constructor(
    private val repo: DirectLinksRepository,
    private val tabRequest: SharesTabRequest,
) : ViewModel() {

    private val _state = MutableStateFlow(DirectLinksUiState())
    val state: StateFlow<DirectLinksUiState> = _state.asStateFlow()

    private val _effects = Channel<DirectLinksEffect>(Channel.BUFFERED)
    val effects: Flow<DirectLinksEffect> = _effects.receiveAsFlow()

    /** 通知点进来时要切到的标签 */
    val pendingTab: StateFlow<SharesTabTarget?> = tabRequest.pending

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var loadedOnce = false

    /** 通知点进来、不在第一页的那条:刷新后仍留在最上面,免得操作表开着时被刷掉 */
    private var pinned: DirectLink? = null

    init {
        checkEnabled()
    }

    fun checkEnabled() {
        if (_state.value.enabled == true) return
        viewModelScope.launch {
            gopanResult { repo.enabled() }.onSuccess { on -> _state.update { it.copy(enabled = on) } }
        }
    }

    fun consumeTab(target: SharesTabTarget) = tabRequest.consume(target)

    /** 切到了「直链」标签:第一次拉列表,之后静默刷新(次数会变) */
    fun onShown() {
        if (loadJob?.isActive == true) return
        load(silent = loadedOnce)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    fun retry() = load(silent = false)

    private fun load(silent: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(status = if (silent) it.status else ListStatus.Loading, loadingMore = false, loadMoreError = null) }
        loadJob = viewModelScope.launch {
            gopanResult { repo.myLinks(cursor = null) }
                .onSuccess { page ->
                    loadedOnce = true
                    val keep = pinned?.takeIf { p -> page.items.none { it.id == p.id } }
                    _state.update {
                        it.copy(
                            status = ListStatus.Content,
                            items = listOfNotNull(keep) + page.items,
                            total = page.total,
                            nextCursor = page.nextCursor,
                            refreshing = false,
                        )
                    }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    val keep = _state.value.status == ListStatus.Content && _state.value.items.isNotEmpty()
                    if (keep) {
                        _state.update { it.copy(refreshing = false) }
                        _effects.trySend(DirectLinksEffect.Message(UiText.Plain(message)))
                    } else {
                        _state.update { it.copy(status = ListStatus.Error(message), refreshing = false) }
                    }
                }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.status != ListStatus.Content || s.loadingMore || loadJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        moreJob = viewModelScope.launch {
            gopanResult { repo.myLinks(cursor) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(items = (it.items + page.items).distinctBy { l -> l.id }, nextCursor = page.nextCursor, loadingMore = false)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, loadMoreError = e.message ?: "加载失败") } }
        }
    }

    /** 通知点进来:列表里有就直接打开;没有(不在第一页)就单独取来放到最上面 */
    fun focus(linkId: String) {
        if (!loadedOnce && loadJob?.isActive != true) load(silent = false)
        viewModelScope.launch {
            loadJob?.join()
            if (_state.value.items.any { it.id == linkId }) {
                _effects.trySend(DirectLinksEffect.Focus(linkId))
                return@launch
            }
            gopanResult { repo.link(linkId) }
                .onSuccess { link ->
                    pinned = link
                    _state.update { it.copy(items = listOf(link) + it.items.filterNot { l -> l.id == link.id }) }
                    _effects.trySend(DirectLinksEffect.Focus(link.id))
                }
                .onFailure { e ->
                    val gone = (e as? GopanException)?.code == GopanErrors.NOT_FOUND
                    _effects.trySend(DirectLinksEffect.Message(if (gone) UiText.Res(R.string.links_msg_gone) else UiText.Plain(e.message ?: "加载失败")))
                }
        }
    }

    fun setPaused(link: DirectLink, paused: Boolean) {
        if (link.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + link.id) }
        viewModelScope.launch {
            gopanResult { repo.pause(link.id, paused) }
                .onSuccess { updated ->
                    if (pinned?.id == updated.id) pinned = updated
                    _state.update { s -> s.copy(busy = s.busy - link.id, items = s.items.map { if (it.id == updated.id) updated else it }) }
                    _effects.trySend(DirectLinksEffect.Message(UiText.Res(if (paused) R.string.links_msg_paused else R.string.links_msg_resumed)))
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = it.busy - link.id) }
                    _effects.trySend(DirectLinksEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }

    fun revoke(link: DirectLink) {
        if (link.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + link.id) }
        viewModelScope.launch {
            val result = gopanResult { repo.revoke(link.id) }
            // 已经不存在(别处吊销过、文件彻底删了)也当成功
            val ok = result.isSuccess || (result.exceptionOrNull() as? GopanException)?.code == GopanErrors.NOT_FOUND
            if (ok && pinned?.id == link.id) pinned = null
            _state.update { s ->
                s.copy(
                    busy = s.busy - link.id,
                    items = if (ok) s.items.filterNot { it.id == link.id } else s.items,
                    total = if (ok) (s.total - 1).coerceAtLeast(0) else s.total,
                )
            }
            _effects.trySend(
                DirectLinksEffect.Message(if (ok) UiText.Res(R.string.links_msg_revoked) else UiText.Plain(result.exceptionOrNull()?.message ?: "吊销失败")),
            )
        }
    }
}

/** 直链列表上的几行字(纯函数,单测直接比资源 id 与参数) */
object DirectLinkTexts {
    fun status(link: DirectLink): UiText = when (link.status) {
        LinkStatus.ACTIVE -> UiText.Res(R.string.links_status_active)
        LinkStatus.PAUSED -> when (link.pauseReason) {
            PAUSE_DAILY_CAP -> UiText.Res(R.string.links_status_paused_cap)
            PAUSE_ANOMALY -> UiText.Res(R.string.links_status_paused_anomaly)
            else -> UiText.Res(R.string.links_status_paused)
        }
        LinkStatus.EXPIRED -> UiText.Res(R.string.links_status_expired)
        LinkStatus.UNAVAILABLE -> UiText.Res(R.string.links_status_unavailable)
    }

    /** 状态的补充说明;正常的没有 */
    fun note(link: DirectLink): UiText? = when (link.status) {
        LinkStatus.ACTIVE -> null
        LinkStatus.PAUSED -> when (link.pauseReason) {
            PAUSE_DAILY_CAP -> UiText.Res(R.string.links_note_cap)
            PAUSE_ANOMALY -> UiText.Res(R.string.links_note_anomaly)
            else -> null
        }
        LinkStatus.EXPIRED -> link.expiresAt?.let { UiText.Res(R.string.links_note_expired, formatTime(it)) }
        LinkStatus.UNAVAILABLE -> UiText.Res(R.string.links_note_unavailable)
    }

    /** 「今天 N 次 · 大小 · 近 30 天 M 次 · 大小」 */
    fun usage(link: DirectLink): UiText = UiText.Res(
        R.string.links_usage,
        link.today.hits,
        formatBytes(link.today.bytes),
        link.last30Days.hits,
        formatBytes(link.last30Days.bytes),
    )

    /** 「最后访问 5 分钟前」/「还没有访问」 */
    fun lastHit(link: DirectLink, now: Instant = Instant.now()): UiText =
        link.lastHitAt?.let { UiText.Res(R.string.links_last_hit, formatRelativeTime(it, now)) } ?: UiText.Res(R.string.links_no_hits)

    /** 暂停只对正常的;恢复只对暂停的(过期、不可用的两样都没有) */
    fun canPause(link: DirectLink): Boolean = link.status == LinkStatus.ACTIVE

    fun canResume(link: DirectLink): Boolean = link.status == LinkStatus.PAUSED

    const val PAUSE_MANUAL = "manual"
    const val PAUSE_DAILY_CAP = "daily_cap"
    const val PAUSE_ANOMALY = "anomaly"
}
