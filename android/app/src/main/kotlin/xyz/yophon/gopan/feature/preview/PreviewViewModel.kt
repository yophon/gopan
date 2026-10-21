package xyz.yophon.gopan.feature.preview

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.archive.ArchiveRepository
import xyz.yophon.gopan.core.data.archive.ExtractRequest
import xyz.yophon.gopan.core.data.preview.PreviewInfo
import xyz.yophon.gopan.core.data.preview.PreviewRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.share.AppNavRequests
import xyz.yophon.gopan.core.share.NavRequest
import xyz.yophon.gopan.core.transfer.TransferActions
import xyz.yophon.gopan.core.data.media.MediaModule
import xyz.yophon.gopan.core.data.media.MediaRepository
import xyz.yophon.gopan.core.data.media.MediaUrls
import xyz.yophon.gopan.core.data.media.NowPlaying
import xyz.yophon.gopan.core.data.media.NowPlayingStore
import xyz.yophon.gopan.core.data.media.PlayProgressReporter
import xyz.yophon.gopan.feature.media.QueuePlan
import xyz.yophon.gopan.feature.media.audioQueue
import xyz.yophon.gopan.feature.media.nextInPlaylist
import xyz.yophon.gopan.feature.archive.ExtractEvent
import xyz.yophon.gopan.feature.archive.ExtractSubmitter
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject
import javax.inject.Named

/** 一次预览会话:左右翻的列表与起始页;[generation] 变了表示换了一批(侧栏预览点了别的文件) */
@Immutable
data class PreviewSessionState(val items: List<PreviewItem>, val initialPage: Int, val generation: Int = 0)

/** 视频放完后要接着放的下一集,连同它所在的列表(不在当前翻页列表里时换成这个列表) */
@Immutable
data class UpNext(val next: Node, val playlist: List<Node>)

sealed interface PreviewEffect {
    data class Message(val text: UiText) : PreviewEffect

    /** Android 9 及以下保存到公共「下载」目录前要申请存储权限;结果回 [PreviewViewModel.onStoragePermission] */
    data object RequestStoragePermission : PreviewEffect

    /** 新任务入队:Android 13+ 第一次时申请通知权限 */
    data object TransferQueued : PreviewEffect

    /** 压缩包的解压任务已提交:Snackbar 带「查看」 */
    data object ExtractStarted : PreviewEffect
}

/**
 * 预览页:左右翻的是打开时那个列表里的文件(文件页、搜索结果、传输助手)。
 * 每页的加载由 [PreviewPage] 负责;这里只留最近 [MAX_PAGES] 页,屏幕上(含相邻预加载的)那几页永不释放。
 */
@HiltViewModel
class PreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: PreviewSessionStore,
    private val repo: PreviewRepository,
    private val transfers: TransferActions,
    /** 视频播放用:对象存储的预签名地址不带凭证,只有本站的外挂字幕(/sub/)带 Bearer */
    @Named(MediaModule.MEDIA_CLIENT) val mediaClient: OkHttpClient,
    private val archives: ArchiveRepository,
    private val navRequests: AppNavRequests,
    private val media: MediaRepository,
    private val reporter: PlayProgressReporter,
    private val mediaUrls: MediaUrls,
    nowPlayingStore: NowPlayingStore,
) : ViewModel() {

    private val handle = savedStateHandle
    private val startId: String = requireNotNull(savedStateHandle.get<String>(NODE_ID_ARG)) { "缺少 nodeId" }

    /** 当前看的那一项:进程重建后从它恢复(会话列表丢了,只剩这一项) */
    private val restoredId: String = savedStateHandle.get<String>(CURRENT_KEY) ?: startId

    private val _session = MutableStateFlow(sessionFor(restoredId, generation = 0))
    val session: StateFlow<PreviewSessionState> = _session.asStateFlow()

    val items: List<PreviewItem> get() = _session.value.items
    val initialPage: Int get() = _session.value.initialPage

    private val _current = MutableStateFlow(_session.value.initialPage)
    val current: StateFlow<Int> = _current.asStateFlow()

    private fun sessionFor(nodeId: String, generation: Int): PreviewSessionState {
        val list = sessions.itemsFor(nodeId)
        return PreviewSessionState(list, list.indexOfFirst { it.nodeId == nodeId }.coerceAtLeast(0), generation)
    }

    /**
     * 侧栏预览(平板双栏)点了另一个文件:按新的会话列表从那个文件开始,旧的页全部释放。
     * 已经停在这个文件上就不动(避免把用户左右翻到的位置拉回去)。
     */
    fun restart(nodeId: String) {
        if (items.getOrNull(_current.value)?.nodeId == nodeId) return
        pages.values.forEach { it.cancel() }
        pages.clear()
        attached.clear()
        val next = sessionFor(nodeId, _session.value.generation + 1)
        _session.value = next
        _current.value = next.initialPage
        handle[CURRENT_KEY] = nodeId
    }

    private val _effects = Channel<PreviewEffect>(Channel.BUFFERED)
    val effects: Flow<PreviewEffect> = _effects.receiveAsFlow()

    /** 按最近使用排序 */
    private val pages = LinkedHashMap<String, PreviewPage>(16, 0.75f, true)
    private val attached = mutableSetOf<String>()
    private var pendingDownload: Node? = null

    /** 取(必要时创建并开始加载)某一项的页面 */
    fun page(nodeId: String): PreviewPage {
        pages[nodeId]?.let { return it }
        val page = PreviewPage(nodeId, repo, viewModelScope, archives)
        pages[nodeId] = page
        page.start()
        trim()
        return page
    }

    fun attach(nodeId: String) {
        attached += nodeId
        pages[nodeId]?.setAttached(true)
    }

    fun detach(nodeId: String) {
        attached -= nodeId
        pages[nodeId]?.setAttached(false)
        trim()
    }

    fun onPageSettled(index: Int) {
        val item = items.getOrNull(index) ?: return
        _current.value = index
        handle[CURRENT_KEY] = item.nodeId
    }

    /** 当前项的节点信息:加载完用服务端的,否则用列表带来的 */
    private fun currentNode(): Node? {
        val item = items.getOrNull(_current.value) ?: return null
        pages[item.nodeId]?.state?.value?.info?.let { return it.toNode() }
        if (item.name.isEmpty()) return null
        return Node(id = item.nodeId, name = item.name, kind = NodeKind.FILE, size = item.size)
    }

    fun download() {
        val node = currentNode() ?: return notLoaded()
        viewModelScope.launch {
            if (transfers.needsStoragePermission()) {
                pendingDownload = node
                send(PreviewEffect.RequestStoragePermission)
                return@launch
            }
            enqueueDownload(node)
        }
    }

    fun onStoragePermission(granted: Boolean) {
        val node = pendingDownload ?: return
        pendingDownload = null
        if (!granted) {
            send(PreviewEffect.Message(UiText.Res(R.string.transfer_msg_need_storage)))
            return
        }
        viewModelScope.launch { enqueueDownload(node) }
    }

    private suspend fun enqueueDownload(node: Node) {
        if (transfers.download(listOf(node)) > 0) {
            send(PreviewEffect.Message(UiText.Res(R.string.drive_msg_download_queued, 1)))
            send(PreviewEffect.TransferQueued)
        }
    }

    /** 下到缓存后交给其他应用(Office 没开转换、PDF 打不开时的兜底) */
    fun openWith() {
        val node = currentNode() ?: return notLoaded()
        viewModelScope.launch {
            if (transfers.open(node)) {
                send(PreviewEffect.Message(UiText.Res(R.string.transfer_msg_opening, node.name)))
                send(PreviewEffect.TransferQueued)
            }
        }
    }

    fun sendToChat() {
        val node = currentNode() ?: return notLoaded()
        viewModelScope.launch {
            gopanResult { transfers.sendToChat(node) }
                .onSuccess { send(PreviewEffect.Message(UiText.Res(R.string.drive_msg_sent_to_chat))) }
                .onFailure { send(PreviewEffect.Message(UiText.Plain(it.message ?: "发送失败"))) }
        }
    }

    // ---- 压缩包卡片 ----

    val extract = ExtractSubmitter(archives, viewModelScope) { event ->
        when (event) {
            is ExtractEvent.Started -> send(PreviewEffect.ExtractStarted)
            is ExtractEvent.Failed -> send(PreviewEffect.Message(UiText.Plain(event.message)))
        }
    }

    /** 「解压到这里」:解到压缩包所在的文件夹 */
    fun extractHere(info: PreviewInfo) = extract.submit(ExtractRequest(nodeId = info.nodeId, parentId = info.parentId))

    /** 「解压到…」:先选目标文件夹 */
    fun extractTo(info: PreviewInfo) = extract.pick(ExtractRequest(nodeId = info.nodeId))

    /** Snackbar 的「查看」:请主界面打开传输列表 */
    fun showTransfers() = navRequests.post(NavRequest.OPEN_JOBS)

    /** 预览里「分享」:当前文件(没加载出信息时给出提示并返回 null) */
    fun shareTarget(): Node? = currentNode() ?: run {
        notLoaded()
        null
    }

    private fun notLoaded() = send(PreviewEffect.Message(UiText.Res(R.string.preview_not_loaded)))

    private fun send(effect: PreviewEffect) {
        _effects.trySend(effect)
    }

    // ---- 影音(doc/22 二) ----

    /** 后台播放服务正在放的:音频页跟着它翻页 */
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingStore.state

    /**
     * 点开一首音频:整个文件夹的音频排进队列(服务端自然顺序),从它开始;查不到列表就只放它自己。
     * 预览页刚取到的地址先交给播放服务,第一首不用再查一次。
     */
    suspend fun loadAudioQueue(nodeId: String, title: String): QueuePlan {
        pages[nodeId]?.state?.value?.info?.contentUrl?.let { mediaUrls.seed(nodeId, it) }
        val playlist = gopanResult { media.playlist(nodeId) }.getOrElse { emptyList() }
        return audioQueue(playlist, nodeId, title)
    }

    /** 视频放完:同文件夹里的下一集(查不到、已是最后一集为 null) */
    suspend fun nextVideo(nodeId: String): UpNext? {
        val playlist = gopanResult { media.playlist(nodeId) }.getOrNull() ?: return null
        val next = nextInPlaylist(playlist, nodeId) ?: return null
        return UpNext(next, playlist)
    }

    /** 下一集不在当前翻页列表里:换成同文件夹的视频列表,从下一集开始 */
    fun openPlaylist(upNext: UpNext) {
        sessions.open(upNext.playlist)
        restart(upNext.next.id)
    }

    fun reportMedia(nodeId: String, positionMs: Long, durationMs: Long) = reporter.reportMedia(nodeId, positionMs, durationMs)

    fun reportPdf(nodeId: String, page: Int, pageCount: Int) = reporter.reportPdf(nodeId, page, pageCount)

    /** 超过上限时释放最久没看、且不在屏幕上的页 */
    private fun trim() {
        if (pages.size <= MAX_PAGES) return
        val iterator = pages.entries.iterator()
        while (pages.size > MAX_PAGES && iterator.hasNext()) {
            val (id, page) = iterator.next()
            if (id in attached) continue
            page.cancel()
            iterator.remove()
        }
    }

    companion object {
        /** 与 Routes.Preview 的属性名一致 */
        const val NODE_ID_ARG = "nodeId"
        private const val CURRENT_KEY = "preview.current"
        const val MAX_PAGES = 6
    }
}
