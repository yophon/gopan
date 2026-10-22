package xyz.yophon.gopan.feature.notifications

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.CachedPage
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.notifications.AppNotification
import xyz.yophon.gopan.core.data.notifications.NotificationsRepository
import xyz.yophon.gopan.core.data.notifications.UnreadNotifications
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.notify.InboxPoster
import xyz.yophon.gopan.core.notify.InboxPrefs
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.shares.SharesTab
import xyz.yophon.gopan.feature.shares.SharesTabRequest
import xyz.yophon.gopan.feature.transfers.TransfersTabRequest
import javax.inject.Inject

@Immutable
data class NotificationsUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<AppNotification> = emptyList(),
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    /** 只看未读 */
    val unreadOnly: Boolean = false,
    /** 全部已读 / 清空进行中 */
    val busy: Boolean = false,
    /** 正在查节点类型(点开了指向文件的通知) */
    val opening: String? = null,
) {
    val hasUnread: Boolean get() = items.any { !it.read }
}

sealed interface NotificationsEffect {
    data class Message(val text: UiText) : NotificationsEffect

    data class Navigate(val nav: NotificationNav) : NotificationsEffect
}

/**
 * 「我的 → 通知」:分页列表、只看未读、全部已读、清空;点一条标已读并跳到它说的地方。
 * 未读角标([UnreadNotifications])随操作即时更新,列表第一页到了再向服务端校正一次。
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repo: NotificationsRepository,
    private val unread: UnreadNotifications,
    private val nodes: NodesRepository,
    private val sessionStore: SessionStore,
    private val sharesTab: SharesTabRequest,
    private val transfersTab: TransfersTabRequest,
    private val cache: ScreenCache,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    private val _effects = Channel<NotificationsEffect>(Channel.BUFFERED)
    val effects: Flow<NotificationsEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var loadedOnce = false

    init {
        // 上次看到的那一页先画出来,再静默刷新
        val cached = cache[cacheKey(unreadOnly = false)]
        if (cached != null) _state.update { it.copy(status = ListStatus.Content, items = cached.items, nextCursor = cached.nextCursor) }
        // 缓存跟着列表走(标已读、清空也算);「全部」「只看未读」各存一份
        cache.mirrorKeyed(viewModelScope, _state) { s ->
            if (s.status == ListStatus.Content) cacheKey(s.unreadOnly) to CachedPage(s.items, s.nextCursor) else null
        }
        load(silent = cached != null)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    fun retry() = load(silent = false)

    /** 回到这一页:静默刷新(期间可能来了新通知) */
    fun onResumed() {
        if (loadedOnce && loadJob?.isActive != true) load(silent = true)
    }

    fun setUnreadOnly(value: Boolean) {
        if (_state.value.unreadOnly == value) return
        // 换筛选与换数据一次写进状态:中间不能出现"新筛选 + 空列表 + Content",否则会被当成空页写进缓存
        val cached = cache[cacheKey(value)]
        _state.update {
            it.copy(
                unreadOnly = value,
                items = cached?.items.orEmpty(),
                nextCursor = cached?.nextCursor,
                status = if (cached != null) ListStatus.Content else ListStatus.Loading,
            )
        }
        load(silent = cached != null)
    }

    /** 换成第一页;进行中的翻页作废 */
    private fun load(silent: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(status = if (silent) it.status else ListStatus.Loading, loadingMore = false, loadMoreError = null) }
        val unreadOnly = _state.value.unreadOnly
        loadJob = viewModelScope.launch {
            gopanResult { repo.list(cursor = null, unreadOnly = unreadOnly) }
                .onSuccess { page ->
                    loadedOnce = true
                    _state.update {
                        it.copy(status = ListStatus.Content, items = page.items, nextCursor = page.nextCursor, refreshing = false)
                    }
                    unread.refresh()
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    val keep = _state.value.status == ListStatus.Content && _state.value.items.isNotEmpty()
                    if (keep) {
                        _state.update { it.copy(refreshing = false) }
                        _effects.trySend(NotificationsEffect.Message(UiText.Plain(message)))
                    } else {
                        _state.update { it.copy(status = ListStatus.Error(message), refreshing = false) }
                    }
                }
        }
    }

    /** 下一页;没有更多、正在拉、第一页还没好时什么都不做。失败留在页脚,点重试再来 */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.status != ListStatus.Content || s.loadingMore || loadJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        val unreadOnly = s.unreadOnly
        moreJob = viewModelScope.launch {
            gopanResult { repo.list(cursor = cursor, unreadOnly = unreadOnly) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(items = (it.items + page.items).distinctBy { n -> n.id }, nextCursor = page.nextCursor, loadingMore = false)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, loadMoreError = e.message ?: "加载失败") } }
        }
    }

    /** 点一条:未读的先标已读(本地立即生效,失败不打扰),再按目标跳转 */
    fun open(notification: AppNotification) {
        if (_state.value.opening != null) return
        if (!notification.read) {
            _state.update { s -> s.copy(items = s.items.map { if (it.id == notification.id) it.copy(read = true) else it }) }
            unread.decrement()
            viewModelScope.launch {
                gopanResult { repo.markRead(listOf(notification.id)) }.onFailure { Timber.w("标已读失败:%s", it.message) }
            }
        }
        val nav = NotificationsLogic.navFor(notification.target, sessionStore.currentUser?.isAdmin == true) ?: return
        when (nav) {
            is NotificationNav.Drive -> openNode(notification.id, nav)
            is NotificationNav.FileRequests -> {
                sharesTab.request(SharesTab.COLLECT)
                navigate(nav)
            }
            is NotificationNav.Links -> {
                sharesTab.request(SharesTab.LINKS, nav.linkId)
                navigate(nav)
            }
            NotificationNav.Jobs -> {
                transfersTab.requestJobs()
                navigate(nav)
            }
            else -> navigate(nav)
        }
    }

    /** 先问节点类型:文件夹直接进去,文件进所在文件夹再预览;删了 / 在回收站里只提示 */
    private fun openNode(notificationId: String, nav: NotificationNav.Drive) {
        val nodeId = nav.previewId ?: return navigate(nav)
        _state.update { it.copy(opening = notificationId) }
        viewModelScope.launch {
            val result = gopanResult { nodes.node(nodeId) }
            _state.update { it.copy(opening = null) }
            result.onSuccess { node ->
                if (node.deletedAt != null) {
                    _effects.trySend(NotificationsEffect.Message(UiText.Res(R.string.notifications_msg_in_trash)))
                } else {
                    navigate(NotificationsLogic.refineNodeNav(nav, node.isFolder))
                }
            }.onFailure { e ->
                if ((e as? GopanException)?.code == GopanErrors.NOT_FOUND) {
                    _effects.trySend(NotificationsEffect.Message(UiText.Res(R.string.notifications_msg_gone)))
                } else {
                    navigate(NotificationsLogic.refineNodeNav(nav, null))
                }
            }
        }
    }

    private fun navigate(nav: NotificationNav) {
        _effects.trySend(NotificationsEffect.Navigate(nav))
    }

    private fun cacheKey(unreadOnly: Boolean) = ScreenKey<CachedPage<AppNotification>>("notifications?unread=$unreadOnly")

    fun markAllRead() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gopanResult { repo.markRead(null) }
                .onSuccess {
                    unread.set(0)
                    _state.update { s ->
                        // 只看未读时已读的就不该留在列表里了
                        val items = if (s.unreadOnly) emptyList() else s.items.map { it.copy(read = true) }
                        s.copy(busy = false, items = items, nextCursor = if (s.unreadOnly) null else s.nextCursor)
                    }
                    _effects.trySend(NotificationsEffect.Message(UiText.Res(R.string.notifications_msg_all_read)))
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    _effects.trySend(NotificationsEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }

    fun clearAll() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gopanResult { repo.clear() }
                .onSuccess {
                    moreJob?.cancel()
                    unread.set(0)
                    _state.update { it.copy(busy = false, items = emptyList(), nextCursor = null, loadingMore = false, loadMoreError = null) }
                    _effects.trySend(NotificationsEffect.Message(UiText.Res(R.string.notifications_msg_cleared)))
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    _effects.trySend(NotificationsEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }
}

/**
 * 通知的入口:底栏「我的」与「我的 → 通知」的未读角标、「在系统通知栏提醒」开关。
 * 主界面在前台时 [pollWhileVisible] 每分钟拉一次未读数。
 */
@HiltViewModel
class NotificationsEntryViewModel @Inject constructor(
    private val unread: UnreadNotifications,
    private val prefs: InboxPrefs,
    private val poster: InboxPoster,
) : ViewModel() {

    val unreadCount: StateFlow<Int> = unread.count

    val systemAlerts: StateFlow<Boolean> = prefs.systemAlerts.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSystemAlerts(on: Boolean) {
        viewModelScope.launch {
            runCatching { prefs.setSystemAlerts(on) }.onFailure { Timber.w(it, "写系统通知开关失败") }
        }
    }

    /** 在 STARTED 期间调用(repeatOnLifecycle):立即拉一次,之后每 [POLL_INTERVAL_MS] 一次,离开前台即停 */
    suspend fun pollWhileVisible() {
        while (true) {
            unread.refresh()
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Android 13+ 通知权限只主动问一次(打开通知页时):开着开关、还没问过才问。返回 true 表示这次该问。
     */
    suspend fun consumePermissionAsk(): Boolean {
        if (!prefs.systemAlerts.first()) return false
        if (prefs.permissionAsked()) return false
        prefs.markPermissionAsked()
        return true
    }

    /** 打开了通知列表:通知栏里已发的站内通知收起来 */
    fun clearPosted() = poster.clearPosted()

    companion object {
        const val POLL_INTERVAL_MS = 60_000L
    }
}
