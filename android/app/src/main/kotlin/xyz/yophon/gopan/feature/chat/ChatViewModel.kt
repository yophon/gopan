package xyz.yophon.gopan.feature.chat

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.chat.ChatMessage
import xyz.yophon.gopan.core.data.chat.ChatRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.core.share.ShareInbox
import xyz.yophon.gopan.core.share.ShareReceiving
import xyz.yophon.gopan.core.transfer.Connectivity
import xyz.yophon.gopan.core.transfer.EnqueueResult
import xyz.yophon.gopan.core.transfer.TransferActions
import xyz.yophon.gopan.core.transfer.TransferEvent
import xyz.yophon.gopan.feature.preview.PreviewSessionStore
import java.io.File
import java.util.UUID
import javax.inject.Inject

@Immutable
data class ChatUiState(
    /** 普通模式 / 定位模式下的消息窗口(旧 → 新) */
    val messages: List<ChatMessage> = emptyList(),
    val pending: List<PendingText> = emptyList(),
    val uploads: List<UploadCard> = emptyList(),
    /** 首次加载中(还没有任何数据) */
    val loading: Boolean = true,
    /** 首次加载失败且没有数据 */
    val loadError: String? = null,
    val hasMoreOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val searchOpen: Boolean = false,
    val searching: Boolean = false,
    /** null:还没搜(或关键词为空) */
    val searchResults: List<ChatMessage>? = null,
    /** 定位模式:窗口不是最新的,轮询不合并;往下滚续页直到追上最新 */
    val anchored: Boolean = false,
    val loadingNewer: Boolean = false,
    val highlightId: String? = null,
    /** 连续两次刷新失败,或系统没网 */
    val offline: Boolean = false,
    val unread: Int = 0,
    val receiving: ShareReceiving? = null,
    /** 最新一条纯文本(全局最新页里的),"复制最新文本"用 */
    val latestText: String? = null,
)

sealed interface ChatEffect {
    data object ScrollToBottom : ChatEffect

    data class ScrollToMessage(val id: String) : ChatEffect

    /** 有新消息:界面在底部附近就滚下去,否则计入未读 */
    data class NewMessages(val count: Int) : ChatEffect

    data class Message(val text: String) : ChatEffect

    data class Copy(val text: String) : ChatEffect

    data class OpenFolder(val folderId: String) : ChatEffect

    /** 打开预览:会话列表(当前显示的消息里的文件)已放进 PreviewSessionStore */
    data class OpenPreview(val nodeId: String) : ChatEffect

    data object TransferQueued : ChatEffect
}

/**
 * 传输助手。行为与网页端一致(doc/05、doc/17),修掉了网页端的几处问题:
 * 按 id 排序与合并、服务端新副本覆盖本地、可以连发、发送前回到最新 / 清搜索、长文本入队成功后才清草稿、
 * 删除时 NOT_FOUND 当成功、上传卡片按 nodeId 等到对应消息出现再撤。
 * 只在页面可见时每 2 秒刷新(进后台、离开这页就停);回到前台、恢复联网时立即刷新一次。
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: ChatRepository,
    private val transfers: TransferActions,
    private val prefs: UserPreferencesRepository,
    private val shareInbox: ShareInbox,
    private val connectivity: Connectivity,
    private val previews: PreviewSessionStore,
    private val cache: ScreenCache,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /**
     * 草稿被 ViewModel 改写(恢复保存的草稿、分享的文本接进来、发送后清空):输入框据此替换内容。
     * 用户打字只走 [setDraft],不发这个事件,免得回写覆盖还没同步过来的按键。
     */
    private val _draftReplace = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val draftReplace: SharedFlow<String> = _draftReplace.asSharedFlow()

    private fun replaceDraft(text: String) {
        _draft.value = text
        _draftReplace.tryEmit(text)
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    /** 刚删掉的 id:挡住删除请求之前发出的轮询带回来的旧数据 */
    private val tombstones = mutableSetOf<String>()
    private var chatFolderId: String? = null
    private var failures = 0
    private var pollJob: Job? = null
    private var visible = false
    private var initialDone = false

    init {
        // 启动时预取(或上次)的最新一页先画出来,不等首次轮询;首次刷新照常合并。
        // 冷启动后内存里还没有:读手机上存的上次那页,通常几十毫秒,赶在整页转圈出现之前
        val cached = cache[LATEST_CACHE_KEY]
        if (cached != null) {
            showCached(cached)
        } else {
            viewModelScope.launch {
                cache.load(LATEST_CACHE_KEY)?.let { if (!initialDone && _state.value.messages.isEmpty()) showCached(it) }
            }
        }
        viewModelScope.launch {
            val saved = runCatching { prefs.chatDraft.first() }.getOrDefault("")
            if (_draft.value.isEmpty() && saved.isNotEmpty()) replaceDraft(saved)
            _draft.drop(1).debounce(DRAFT_SAVE_DEBOUNCE_MS).collect { runCatching { prefs.setChatDraft(it) } }
        }
        viewModelScope.launch {
            chatFolderId = gopanResult { repo.folderId() }.getOrNull()
        }
        // 上传卡片:发往传输助手的任务 + 实时进度;完成的等消息出现再撤
        viewModelScope.launch {
            combine(transfers.uploadTasks, transfers.uploadProgress, _state.map { it.messages }.distinctUntilChanged()) { tasks, progress, messages ->
                val nodeIds = messages.mapNotNullTo(HashSet()) { it.node?.id }
                ChatLogic.uploadCards(tasks, progress, chatFolderId, nodeIds, System.currentTimeMillis())
            }.collect { cards -> _state.update { it.copy(uploads = cards) } }
        }
        // 上传落进传输助手:立刻刷新一次(消息在服务端定稿事务里已经落下了)
        viewModelScope.launch {
            transfers.events.filter { it is TransferEvent.UploadDone && it.task.toChat }.collect { if (visible) refreshLatest() }
        }
        viewModelScope.launch {
            connectivity.online.collect { online ->
                if (!online) {
                    _state.update { it.copy(offline = true) }
                } else if (visible && initialDone) {
                    refreshLatest()
                }
            }
        }
        viewModelScope.launch { shareInbox.receiving.collect { r -> _state.update { it.copy(receiving = r) } } }
        viewModelScope.launch { shareInbox.pending.filterNotNull().collect { consumeShare() } }
    }

    private fun showCached(latest: List<ChatMessage>) {
        _state.update {
            it.copy(
                messages = latest,
                loading = false,
                loadError = null,
                hasMoreOlder = latest.size >= ChatRepository.LATEST_LIMIT,
                latestText = ChatLogic.latestText(latest),
            )
        }
    }

    // ---- 可见性与轮询 ----

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        pollJob?.cancel()
        if (value) {
            pollJob = viewModelScope.launch {
                while (true) {
                    refreshLatest()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun retryInitial() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch { refreshLatest() }
    }

    private suspend fun refreshLatest() {
        val result = gopanResult { repo.latest(ChatRepository.LATEST_LIMIT) }
        result.onSuccess { latest ->
            failures = 0
            val first = !initialDone
            initialDone = true
            val visibleLatest = latest.filter { it.id !in tombstones }
            cache.put(LATEST_CACHE_KEY, visibleLatest)
            var added = 0
            _state.update { s ->
                val base = s.copy(loading = false, loadError = null, offline = !connectivity.online.value, latestText = ChatLogic.latestText(visibleLatest))
                if (s.anchored) {
                    base
                } else {
                    val (merged, n) = ChatLogic.mergeLatest(s.messages, latest, tombstones, ChatRepository.LATEST_LIMIT)
                    added = n
                    base.copy(
                        messages = merged,
                        hasMoreOlder = if (first) latest.size >= ChatRepository.LATEST_LIMIT else s.hasMoreOlder,
                    )
                }
            }
            when {
                first -> _effects.trySend(ChatEffect.ScrollToBottom)
                added > 0 -> _effects.trySend(ChatEffect.NewMessages(added))
            }
        }.onFailure { e ->
            failures++
            _state.update {
                it.copy(
                    offline = failures >= 2 || !connectivity.online.value,
                    loading = false,
                    loadError = if (!initialDone && it.messages.isEmpty()) e.message ?: "加载失败" else it.loadError,
                )
            }
        }
    }

    /** 滚到顶附近:往前翻 100 条 */
    fun loadOlder() {
        val s = _state.value
        val oldest = s.messages.firstOrNull() ?: return
        if (s.loadingOlder || !s.hasMoreOlder || s.searchOpen) return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            gopanResult { repo.before(oldest.id, ChatRepository.HISTORY_PAGE) }
                .onSuccess { older ->
                    _state.update {
                        it.copy(
                            messages = ChatLogic.mergeWindow(it.messages, older, tombstones),
                            hasMoreOlder = older.size >= ChatRepository.HISTORY_PAGE,
                            loadingOlder = false,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loadingOlder = false) }
                    _effects.trySend(ChatEffect.Message(e.message ?: "加载更早的消息失败"))
                }
        }
    }

    /** 定位模式滚到底附近:往后续 50 条,不满 50 条说明追上最新,退出定位模式 */
    fun loadNewer() {
        val s = _state.value
        val newest = s.messages.lastOrNull() ?: return
        if (!s.anchored || s.loadingNewer) return
        _state.update { it.copy(loadingNewer = true) }
        viewModelScope.launch {
            gopanResult { repo.after(newest.id, ChatRepository.ANCHOR_PAGE) }
                .onSuccess { newer ->
                    val caughtUp = newer.size < ChatRepository.ANCHOR_PAGE
                    _state.update {
                        it.copy(
                            messages = ChatLogic.mergeWindow(it.messages, newer, tombstones),
                            anchored = !caughtUp,
                            loadingNewer = false,
                        )
                    }
                    if (caughtUp) refreshLatest()
                }
                .onFailure { _state.update { it.copy(loadingNewer = false) } }
        }
    }

    /** 回到最新:丢掉定位窗口,重拉最新一页 */
    fun backToLatest() {
        _state.update { it.copy(anchored = false, messages = emptyList(), loading = true, highlightId = null, unread = 0) }
        initialDone = false
        viewModelScope.launch { refreshLatest() }
    }

    fun clearUnread() = _state.update { it.copy(unread = 0) }

    fun addUnread(n: Int) = _state.update { it.copy(unread = it.unread + n) }

    // ---- 搜索与定位 ----

    private var searchJob: Job? = null

    fun openSearch() = _state.update { it.copy(searchOpen = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _state.update { it.copy(searchOpen = false, searching = false, searchResults = null) }
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        val term = q.trim()
        if (term.isEmpty()) {
            _state.update { it.copy(searching = false, searchResults = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            gopanResult { repo.search(term) }
                .onSuccess { hits -> _state.update { it.copy(searching = false, searchResults = hits.filter { m -> m.id !in tombstones }) } }
                .onFailure { e ->
                    _state.update { it.copy(searching = false) }
                    _effects.trySend(ChatEffect.Message(e.message ?: "搜索失败"))
                }
        }
    }

    /** 定位到某条消息:前 50 + 命中 + 后 50 组成窗口,高亮 1.5 秒 */
    fun locate(hit: ChatMessage) {
        viewModelScope.launch {
            val older = async { gopanResult { repo.before(hit.id, ChatRepository.ANCHOR_PAGE) } }
            val newer = async { gopanResult { repo.after(hit.id, ChatRepository.ANCHOR_PAGE) } }
            val o = older.await().getOrElse {
                _effects.trySend(ChatEffect.Message(it.message ?: "定位失败"))
                return@launch
            }
            val n = newer.await().getOrElse {
                _effects.trySend(ChatEffect.Message(it.message ?: "定位失败"))
                return@launch
            }
            val window = ChatLogic.mergeWindow(emptyList(), o + hit + n, tombstones)
            val caughtUp = n.size < ChatRepository.ANCHOR_PAGE
            searchJob?.cancel()
            _searchQuery.value = ""
            _state.update {
                it.copy(
                    messages = window,
                    anchored = !caughtUp,
                    hasMoreOlder = o.size >= ChatRepository.ANCHOR_PAGE,
                    searchOpen = false,
                    searching = false,
                    searchResults = null,
                    highlightId = hit.id,
                    unread = 0,
                )
            }
            _effects.trySend(ChatEffect.ScrollToMessage(hit.id))
            if (caughtUp) refreshLatest()
            delay(HIGHLIGHT_MS)
            _state.update { if (it.highlightId == hit.id) it.copy(highlightId = null) else it }
        }
    }

    // ---- 发送 ----

    fun setDraft(text: String) {
        _draft.value = text
    }

    /** 发送草稿:超过 4000 字节转成 txt 文件发;否则乐观上屏 */
    fun send() {
        val body = _draft.value.trim()
        if (body.isEmpty()) return
        leaveSpecialModes()
        if (ChatLogic.utf8Length(body) > ChatRepository.MAX_TEXT_BYTES) {
            viewModelScope.launch {
                val folder = ensureFolder() ?: return@launch
                gopanResult { transfers.uploadText(body, folder, toChat = true) }
                    .onSuccess {
                        replaceDraft("")
                        _effects.trySend(ChatEffect.Message("文本超过 4000 字节,已转成 txt 文件发送"))
                        _effects.trySend(ChatEffect.ScrollToBottom)
                        _effects.trySend(ChatEffect.TransferQueued)
                    }
                    .onFailure { _effects.trySend(ChatEffect.Message(it.message ?: "发送失败")) }
            }
            return
        }
        val pending = PendingText("pending-${UUID.randomUUID()}", body, System.currentTimeMillis())
        replaceDraft("")
        _state.update { it.copy(pending = it.pending + pending) }
        _effects.trySend(ChatEffect.ScrollToBottom)
        sendPending(pending)
    }

    private fun sendPending(p: PendingText) {
        viewModelScope.launch {
            gopanResult { repo.sendText(p.body) }
                .onSuccess { msg ->
                    _state.update {
                        it.copy(
                            pending = it.pending.filterNot { x -> x.id == p.id },
                            messages = ChatLogic.mergeWindow(it.messages, listOf(msg), tombstones),
                        )
                    }
                    _effects.trySend(ChatEffect.ScrollToBottom)
                }
                .onFailure {
                    _state.update { s -> s.copy(pending = s.pending.map { x -> if (x.id == p.id) x.copy(failed = true) else x }) }
                    _effects.trySend(ChatEffect.Message("消息发送失败"))
                }
        }
    }

    fun retryPending(id: String) {
        val p = _state.value.pending.firstOrNull { it.id == id } ?: return
        val again = p.copy(failed = false)
        _state.update { s -> s.copy(pending = s.pending.filterNot { it.id == id } + again) }
        sendPending(again)
    }

    fun removePending(id: String) = _state.update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) }

    fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        leaveSpecialModes()
        viewModelScope.launch {
            val folder = ensureFolder() ?: return@launch
            report(transfers.uploadUris(uris, folder, toChat = true))
        }
    }

    /** 粘贴 / 键盘插入的图片 */
    fun sendPasted(uris: List<Uri>) {
        if (uris.isEmpty()) return
        leaveSpecialModes()
        viewModelScope.launch {
            val folder = ensureFolder() ?: return@launch
            report(transfers.uploadCopies(uris, folder, toChat = true))
        }
    }

    fun sendFiles(files: List<File>) {
        if (files.isEmpty()) return
        leaveSpecialModes()
        viewModelScope.launch {
            val folder = ensureFolder() ?: return@launch
            report(transfers.uploadOwnedFiles(files, folder, toChat = true))
        }
    }

    private fun report(r: EnqueueResult) {
        if (r.queued > 0) {
            _effects.trySend(ChatEffect.ScrollToBottom)
            _effects.trySend(ChatEffect.TransferQueued)
        }
        if (r.emptySkipped > 0) _effects.trySend(ChatEffect.Message("已跳过 ${r.emptySkipped} 个空文件"))
        if (r.unreadable > 0) _effects.trySend(ChatEffect.Message("${r.unreadable} 个文件读取失败"))
    }

    private suspend fun ensureFolder(): String? =
        gopanResult { repo.ensureFolder() }
            .onSuccess { chatFolderId = it }
            .onFailure { _effects.trySend(ChatEffect.Message("无法创建传输助手文件夹:${it.message ?: ""}")) }
            .getOrNull()

    /** 发送前:清掉搜索、离开定位模式(否则自己发的消息看不到,或排在窗口末尾留下缺口) */
    private fun leaveSpecialModes() {
        val s = _state.value
        if (s.searchOpen) closeSearch()
        if (s.anchored) backToLatest()
    }

    /** 系统分享:文本接到草稿后面(不自动发),文件直接发 */
    private fun consumeShare() {
        val payload = shareInbox.take() ?: return
        payload.text?.let { text ->
            val current = _draft.value
            replaceDraft(if (current.isBlank()) text else current.trimEnd() + "\n" + text)
        }
        if (payload.files.isNotEmpty()) sendFiles(payload.files)
        if (payload.failed > 0) _effects.trySend(ChatEffect.Message("${payload.failed} 个分享的文件读取失败"))
    }

    // ---- 消息操作 ----

    fun delete(m: ChatMessage) {
        viewModelScope.launch {
            gopanResult { repo.delete(m.id) }
                .onSuccess {
                    tombstones += m.id
                    _state.update {
                        it.copy(
                            messages = it.messages.filterNot { x -> x.id == m.id },
                            searchResults = it.searchResults?.filterNot { x -> x.id == m.id },
                        )
                    }
                }
                .onFailure { _effects.trySend(ChatEffect.Message(it.message ?: "删除失败")) }
        }
    }

    fun copy(m: ChatMessage) {
        val text = m.node?.name ?: m.body
        if (text.isNotEmpty()) _effects.trySend(ChatEffect.Copy(text))
    }

    fun copyLatestText() {
        _state.value.latestText?.let { _effects.trySend(ChatEffect.Copy(it)) }
    }

    fun download(node: Node) {
        viewModelScope.launch {
            val n = gopanResult { transfers.download(listOf(node)) }.getOrDefault(0)
            if (n > 0) {
                _effects.trySend(ChatEffect.Message("已加入下载队列"))
                _effects.trySend(ChatEffect.TransferQueued)
            }
        }
    }

    fun open(node: Node) {
        if (node.isFolder) {
            _effects.trySend(ChatEffect.OpenFolder(node.id))
            return
        }
        viewModelScope.launch {
            if (gopanResult { transfers.open(node) }.getOrDefault(false)) {
                _effects.trySend(ChatEffect.Message("正在准备「${node.name}」…"))
                _effects.trySend(ChatEffect.TransferQueued)
            }
        }
    }

    /**
     * 点文件 / 图片:全屏预览,左右翻的是当前显示的消息(搜索时是搜索结果)里的全部文件,
     * 从旧到新(与网页端一致)。文件夹直接进文件页。
     */
    fun preview(node: Node) {
        if (node.isFolder) {
            _effects.trySend(ChatEffect.OpenFolder(node.id))
            return
        }
        val s = _state.value
        val shown = if (s.searchOpen && s.searchResults != null) s.searchResults else s.messages
        val files = shown.mapNotNull { it.node }.filter { !it.isFolder }.distinctBy { it.id }
        previews.open(if (files.any { it.id == node.id }) files else listOf(node))
        _effects.trySend(ChatEffect.OpenPreview(node.id))
    }

    /** 在文件页里看这个文件所在的目录 */
    fun locateInDrive(node: Node) {
        val parent = node.parentId ?: return
        _effects.trySend(ChatEffect.OpenFolder(parent))
    }

    fun retryUpload(id: Long) = viewModelScope.launch { transfers.retryUpload(id) }

    fun cancelUpload(id: Long) = viewModelScope.launch { transfers.cancelUpload(id) }

    fun removeUpload(id: Long) = viewModelScope.launch { transfers.removeUpload(id) }

    override fun onCleared() {
        // 草稿最后一次落盘(debounce 可能还没到);ViewModel 的作用域已经取消,用一次性的作用域
        val text = _draft.value
        CoroutineScope(Dispatchers.IO).launch { runCatching { prefs.setChatDraft(text) } }
    }

    companion object {
        /** 最新一页([ChatRepository.LATEST_LIMIT] 条,升序);启动预取也写这里。落盘,冷启动后也有 */
        val LATEST_CACHE_KEY = ScreenKey("chat.latest", ListSerializer(ChatMessage.serializer()))

        const val POLL_INTERVAL_MS = 2_000L
        const val SEARCH_DEBOUNCE_MS = 300L
        const val HIGHLIGHT_MS = 1_500L
        const val DRAFT_SAVE_DEBOUNCE_MS = 500L
    }
}
