package xyz.yophon.gopan.feature.collect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import xyz.yophon.gopan.core.data.CachedPage
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.collect.FileRequestUpload
import xyz.yophon.gopan.core.data.collect.FileRequestsRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.ListSkeleton
import xyz.yophon.gopan.core.ui.format.describeUserAgent
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.resolve
import javax.inject.Inject

@Immutable
data class FileRequestUploadsUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<FileRequestUpload> = emptyList(),
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

/**
 * 一条收集链接的收件记录:新的在前、按 cursor 一页 50 条,滑到底自动续拉。
 * 打开时若有新收到的(路由带的 newCount > 0)就标为已读;失败不影响查看。
 */
@HiltViewModel
class FileRequestUploadsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repo: FileRequestsRepository,
    private val cache: ScreenCache,
) : ViewModel() {
    val requestId: String = requireNotNull(savedStateHandle.get<String>("requestId"))
    val title: String = savedStateHandle.get<String>("title").orEmpty()
    private val cacheKey = ScreenKey<CachedPage<FileRequestUpload>>("file-request-uploads/$requestId")

    private val _state = MutableStateFlow(FileRequestUploadsUiState())
    val state: StateFlow<FileRequestUploadsUiState> = _state.asStateFlow()

    /** 下拉刷新失败(手上已有列表)时的提示 */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var loadJob: Job? = null
    private var moreJob: Job? = null

    init {
        // 旋转屏幕不重复标;进程被回收后重建再标一次也无妨(幂等)
        if ((savedStateHandle.get<Int>("newCount") ?: 0) > 0 && savedStateHandle.get<Boolean>(KEY_MARKED) != true) {
            savedStateHandle[KEY_MARKED] = true
            viewModelScope.launch { gopanResult { repo.markSeen(requestId) } }
        }
        cache.mirror(viewModelScope, cacheKey, _state) { s -> CachedPage(s.items, s.nextCursor).takeIf { s.status == ListStatus.Content } }
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    fun retry() = load(silent = false)

    /** 换成第一页(首次进入、下拉刷新、重试);进行中的翻页作废。非静默时有快照就先画快照,联网按静默刷新处理 */
    private fun load(silent: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        val cached = if (silent) null else cache[cacheKey]
        _state.update {
            if (cached != null) {
                it.copy(status = ListStatus.Content, items = cached.items, nextCursor = cached.nextCursor, loadingMore = false, loadMoreError = null)
            } else {
                it.copy(status = if (silent) it.status else ListStatus.Loading, loadingMore = false, loadMoreError = null)
            }
        }
        loadJob = viewModelScope.launch {
            gopanResult { repo.uploads(requestId, cursor = null) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(status = ListStatus.Content, items = page.items, nextCursor = page.nextCursor, refreshing = false)
                    }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    val keep = _state.value.status == ListStatus.Content && _state.value.items.isNotEmpty()
                    if (keep) {
                        _state.update { it.copy(refreshing = false) }
                        _messages.trySend(message)
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
        moreJob = viewModelScope.launch {
            gopanResult { repo.uploads(requestId, cursor) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(items = (it.items + page.items).distinctBy { u -> u.id }, nextCursor = page.nextCursor, loadingMore = false)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, loadMoreError = e.message ?: "加载失败") } }
        }
    }

    companion object {
        private const val KEY_MARKED = "seenMarked"

        /** 上传者一栏:自填的名字,没填显示「未署名」 */
        fun uploaderText(upload: FileRequestUpload): UiText =
            upload.uploaderName.trim().takeIf { it.isNotEmpty() }?.let { UiText.Plain(it) } ?: UiText.Res(R.string.collect_uploads_anonymous)
    }
}

/** 收件记录。点一条(文件还在)跳到它现在所在的目录;删掉的文件划掉、灰掉,记录仍在 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileRequestUploadsScreen(
    onBack: () -> Unit,
    onOpenFolder: (folderId: String?) -> Unit,
    viewModel: FileRequestUploadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { launch { snackbar.showSnackbar(it) } }
    }
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedScreenInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.collect_uploads_title))
                        if (viewModel.title.isNotEmpty()) {
                            Text(
                                viewModel.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when (val status = state.status) {
                ListStatus.Loading -> ListSkeleton()
                is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                ListStatus.Content -> if (state.items.isEmpty()) {
                    FullPage {
                        EmptyState(
                            title = stringResource(R.string.collect_uploads_empty),
                            description = stringResource(R.string.collect_uploads_note),
                            icon = Icons.Outlined.MoveToInbox,
                        )
                    }
                } else {
                    UploadList(state, viewModel, onOpen = { upload -> if (!upload.fileGone) onOpenFolder(upload.parentId) })
                }
            }
        }
    }
}

@Composable
private fun UploadList(
    state: FileRequestUploadsUiState,
    viewModel: FileRequestUploadsViewModel,
    onOpen: (FileRequestUpload) -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreTrigger(itemCount = state.items.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("collect_uploads_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(state.items, key = { it.id }) { upload ->
            UploadRow(upload, uploader = FileRequestUploadsViewModel.uploaderText(upload).resolve(), onClick = { onOpen(upload) })
        }
        if (state.nextCursor != null || state.loadingMore || state.loadMoreError != null) {
            item(key = "footer") {
                LoadMoreFooter(isLoading = state.loadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore)
            }
        }
        item(key = "note") {
            Text(
                stringResource(R.string.collect_uploads_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun UploadRow(upload: FileRequestUpload, uploader: String, onClick: () -> Unit) {
    val gone = upload.fileGone
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = Modifier.clickable(enabled = !gone, onClick = onClick).testTag("collect_upload_row"),
        leadingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = if (gone) stringResource(R.string.collect_uploads_gone) else null,
                tint = if (gone) dim.copy(alpha = 0.5f) else dim,
            )
        },
        headlineContent = {
            Text(
                upload.fileName,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                color = if (gone) dim else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (gone) TextDecoration.LineThrough else null,
            )
        },
        supportingContent = {
            Column {
                Text("$uploader · ${formatBytes(upload.size)} · ${formatTime(upload.createdAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(upload.ip ?: "—", describeUserAgent(upload.userAgent)).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
