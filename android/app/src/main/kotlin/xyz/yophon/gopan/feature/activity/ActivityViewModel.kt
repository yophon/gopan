package xyz.yophon.gopan.feature.activity

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
import timber.log.Timber
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.CachedPage
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.activity.Activity
import xyz.yophon.gopan.core.data.activity.ActivityActor
import xyz.yophon.gopan.core.data.activity.ActivityQuery
import xyz.yophon.gopan.core.data.activity.ActivityRepository
import xyz.yophon.gopan.core.data.activity.ActorSummary
import xyz.yophon.gopan.core.data.activity.UndoOutcome
import xyz.yophon.gopan.core.data.activity.UndoTarget
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.notifications.NotificationNav
import xyz.yophon.gopan.feature.notifications.NotificationsLogic
import javax.inject.Inject

/** 整批撤回的确认:先预览过,[preview] 里是会撤回几项、哪些撤不了 */
@Immutable
data class UndoConfirm(val target: UndoTarget, val preview: UndoOutcome) {
    /** 按来源全部撤回(否则是一组) */
    val all: Boolean get() = target is UndoTarget.Filter
}

@Immutable
data class ActivityUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<Activity> = emptyList(),
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val query: ActivityQuery = ActivityQuery(),
    /** 筛选条上的来源;拉不到(旧服务端、断网)时为空,只剩「全部来源」 */
    val actors: List<ActorSummary> = emptyList(),
    /** 展开的批次 */
    val expanded: Set<String> = emptySet(),
    /** 正在撤回 / 预览的:单条是活动 id,一组是 batchId,全部撤回是 [ALL_KEY] */
    val working: String? = null,
    val confirm: UndoConfirm? = null,
) {
    /** 按来源筛着才能「全部撤回」(服务端要求至少给来源、时间或文件) */
    val canUndoAll: Boolean get() = query.actorKind != null && items.any { it.undoable && !it.undone }

    companion object {
        const val ALL_KEY = "*"
    }
}

sealed interface ActivityEffect {
    data class Message(val text: UiText) : ActivityEffect

    /** 打开节点:文件夹进去,文件进所在文件夹再预览(与通知的 NODE 目标同一套) */
    data class Open(val nav: NotificationNav.Drive) : ActivityEffect
}

/**
 * 「我的 → 动态」(doc/23 二.4):按天分组、同一批折叠、按来源筛选、单条与整批撤回,大量删除提醒点进来时「全部撤回」。
 * 路由参数 actor(kind:ref)/ since 来自通知目标。撤回之后静默刷新(撤回本身也是一批新动态),
 * 并让文件列表回到前台时刷新。
 */
@HiltViewModel
class ActivityViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ActivityRepository,
    private val nodes: NodesRepository,
    private val cache: ScreenCache,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ActivityUiState(query = ActivityLogic.queryFor(savedStateHandle.get<String>(ACTOR_ARG), savedStateHandle.get<String>(SINCE_ARG))),
    )
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    private val _effects = Channel<ActivityEffect>(Channel.BUFFERED)
    val effects: Flow<ActivityEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var loadedOnce = false

    init {
        // 上次看到的(同一筛选下的)第一页与来源条先画出来,再静默刷新
        val cached = cache[cacheKey(_state.value.query)]
        val actors = cache[ACTORS_KEY]
        _state.update {
            it.copy(
                status = if (cached != null) ListStatus.Content else it.status,
                items = cached?.items ?: it.items,
                nextCursor = cached?.nextCursor,
                actors = actors ?: it.actors,
            )
        }
        // 缓存跟着列表走(撤回后的刷新也算);每种筛选各存一份
        cache.mirrorKeyed(viewModelScope, _state) { s ->
            if (s.status == ListStatus.Content) cacheKey(s.query) to CachedPage(s.items, s.nextCursor) else null
        }
        load(silent = cached != null)
        loadActors()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
        loadActors()
    }

    fun retry() = load(silent = false)

    /** 回到这一页:静默刷新(期间可能在别处改过东西) */
    fun onResumed() {
        if (loadedOnce && loadJob?.isActive != true && _state.value.working == null) load(silent = true)
    }

    private fun loadActors() {
        viewModelScope.launch {
            gopanResult { repo.actors() }
                .onSuccess { actors ->
                    cache.put(ACTORS_KEY, actors)
                    _state.update { it.copy(actors = actors) }
                }
                .onFailure { Timber.w("来源列表拉取失败:%s", it.message) }
        }
    }

    /** 换成第一页;进行中的翻页作废 */
    private fun load(silent: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(status = if (silent) it.status else ListStatus.Loading, loadingMore = false, loadMoreError = null) }
        val query = _state.value.query
        loadJob = viewModelScope.launch {
            gopanResult { repo.list(query, cursor = null) }
                .onSuccess { page ->
                    loadedOnce = true
                    _state.update { it.copy(status = ListStatus.Content, items = page.items, nextCursor = page.nextCursor, refreshing = false) }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    val keep = _state.value.status == ListStatus.Content && _state.value.items.isNotEmpty()
                    if (keep) {
                        _state.update { it.copy(refreshing = false) }
                        _effects.trySend(ActivityEffect.Message(UiText.Plain(message)))
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
        val query = s.query
        moreJob = viewModelScope.launch {
            gopanResult { repo.list(query, cursor) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(items = (it.items + page.items).distinctBy { a -> a.id }, nextCursor = page.nextCursor, loadingMore = false)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, loadMoreError = e.message ?: "加载失败") } }
        }
    }

    // ---- 筛选 ----

    /** 按来源筛;null = 全部来源。「从什么时候起」保留(通知点进来的时间段),单独清掉 */
    fun selectActor(actor: ActivityActor?) {
        val current = _state.value.query
        val next = current.copy(actorKind = actor?.kind, actorRef = actor?.ref?.takeIf { it.isNotEmpty() })
        setQuery(next)
    }

    fun clearSince() = setQuery(_state.value.query.copy(since = null))

    private fun setQuery(query: ActivityQuery) {
        if (query == _state.value.query) return
        // 换筛选与换数据一次写进状态:中间不能出现"新筛选 + 空列表 + Content",否则会被当成空页写进缓存
        val cached = cache[cacheKey(query)]
        _state.update {
            it.copy(
                query = query,
                items = cached?.items.orEmpty(),
                nextCursor = cached?.nextCursor,
                expanded = emptySet(),
                status = if (cached != null) ListStatus.Content else ListStatus.Loading,
            )
        }
        load(silent = cached != null)
    }

    fun toggleGroup(batchId: String) {
        _state.update { s -> s.copy(expanded = if (batchId in s.expanded) s.expanded - batchId else s.expanded + batchId) }
    }

    // ---- 打开 ----

    fun open(activity: Activity) {
        val node = activity.node
        if (node == null) {
            if (activity.nodeId != null) _effects.trySend(ActivityEffect.Message(UiText.Res(R.string.activity_msg_node_gone)))
            return
        }
        val nav = NotificationNav.Drive(folderId = node.parentId, previewId = node.id)
        _effects.trySend(ActivityEffect.Open(NotificationsLogic.refineNodeNav(nav, node.isFolder)))
    }

    // ---- 撤回 ----

    /** 单条:直接撤,撤不了的原因在提示里 */
    fun undo(activity: Activity) {
        if (_state.value.working != null || !activity.undoable || activity.undone) return
        runUndo(activity.id, UndoTarget.Ids(listOf(activity.id)))
    }

    /** 一组:先预览,确认后再撤 */
    fun requestUndoGroup(batchId: String) = requestPreview(batchId, UndoTarget.Batch(batchId))

    /** 按当前来源(和时间段)全部撤回:先预览 */
    fun requestUndoAll() {
        val query = _state.value.query
        if (query.actorKind == null) return
        requestPreview(ActivityUiState.ALL_KEY, UndoTarget.Filter(query))
    }

    private fun requestPreview(key: String, target: UndoTarget) {
        if (_state.value.working != null) return
        _state.update { it.copy(working = key) }
        viewModelScope.launch {
            gopanResult { repo.preview(target) }
                .onSuccess { outcome -> _state.update { it.copy(working = null, confirm = UndoConfirm(target, outcome)) } }
                .onFailure { e ->
                    _state.update { it.copy(working = null) }
                    _effects.trySend(ActivityEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }

    fun dismissConfirm() {
        if (_state.value.working == null) _state.update { it.copy(confirm = null) }
    }

    fun confirmUndo() {
        val confirm = _state.value.confirm ?: return
        if (_state.value.working != null) return
        val key = when (val t = confirm.target) {
            is UndoTarget.Batch -> t.batchId
            is UndoTarget.Ids -> t.ids.firstOrNull().orEmpty()
            is UndoTarget.Filter -> ActivityUiState.ALL_KEY
        }
        runUndo(key, confirm.target)
    }

    private fun runUndo(key: String, target: UndoTarget) {
        _state.update { it.copy(working = key) }
        viewModelScope.launch {
            gopanResult { repo.undo(target) }
                .onSuccess { outcome ->
                    _state.update { it.copy(working = null, confirm = null) }
                    _effects.trySend(ActivityEffect.Message(UiText.Plain(ActivityLogic.resultText(outcome))))
                    if (outcome.done > 0) {
                        // 文件列表、回收站回到前台时刷新;动态本身多了撤回那一批、原记录标上已撤回
                        nodes.markChanged()
                        load(silent = true)
                        loadActors()
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(working = null) }
                    _effects.trySend(ActivityEffect.Message(UiText.Plain(e.message ?: "操作失败")))
                }
        }
    }

    companion object {
        /** 路由参数名(Routes.Activity 的字段名) */
        const val ACTOR_ARG = "actor"
        const val SINCE_ARG = "since"

        val ACTORS_KEY = ScreenKey<List<ActorSummary>>("activity-actors")

        /** 第一页按筛选各存一份 */
        fun cacheKey(query: ActivityQuery) =
            ScreenKey<CachedPage<Activity>>("activity?actor=${query.actorKind}:${query.actorRef.orEmpty()}&since=${query.since.orEmpty()}")
    }
}
