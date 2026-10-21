package xyz.yophon.gopan.feature.collect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
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
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.data.collect.FileRequest
import xyz.yophon.gopan.core.data.collect.FileRequestsRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.drive.resolve
import java.time.Instant
import javax.inject.Inject

/** 列表与操作表上的几行字(纯函数,单测直接比资源 id 与参数) */
object FileRequestTexts {
    /** 「收到 N 个 · 大小 · M 人」或「还没收到」 */
    fun received(r: FileRequest): UiText =
        if (r.fileCount == 0) UiText.Res(R.string.collect_received_none)
        else UiText.Res(R.string.collect_received, r.fileCount, formatBytes(r.totalBytes), r.uploaderCount)

    /** 「截止 …」/「已截止 …」/「不限时间」 */
    fun deadline(r: FileRequest, now: Instant = Instant.now()): UiText = when {
        r.expiresAt == null -> UiText.Res(R.string.collect_no_deadline)
        r.isExpired(now) -> UiText.Res(R.string.collect_closed_at, formatTime(r.expiresAt))
        else -> UiText.Res(R.string.collect_until, formatTime(r.expiresAt))
    }

    /** 操作表头部的第二行:留名方式与各项上限(没有就不出现) */
    fun details(r: FileRequest): List<UiText> = buildList {
        when {
            r.groupByUploader -> add(UiText.Res(R.string.collect_flag_group))
            r.requireName -> add(UiText.Res(R.string.collect_flag_require_name))
        }
        r.maxFiles?.let { add(UiText.Res(R.string.collect_limit_files, it)) }
        r.maxTotalBytes?.let { add(UiText.Res(R.string.collect_limit_total, formatBytes(it))) }
        r.maxFileSize?.let { add(UiText.Res(R.string.collect_limit_file_size, formatBytes(it))) }
    }
}

@Immutable
data class FileRequestsUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<FileRequest> = emptyList(),
    val refreshing: Boolean = false,
    /** 正在结束的链接 id(按钮转圈、防连点) */
    val revoking: Set<String> = emptySet(),
)

sealed interface FileRequestsEffect {
    data class Message(val text: UiText) : FileRequestsEffect
}

/** 「分享」页的「收集」标签:我开的收集链接;复制 / 发送 / 打开交给系统,结束收集在这里 */
@HiltViewModel
class FileRequestsViewModel @Inject constructor(
    private val repo: FileRequestsRepository,
    private val config: AppConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(FileRequestsUiState())
    val state: StateFlow<FileRequestsUiState> = _state.asStateFlow()

    private val _effects = Channel<FileRequestsEffect>(Channel.BUFFERED)
    val effects: Flow<FileRequestsEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        load(silent = false)
    }

    fun url(request: FileRequest): String = config.collectUrl(request.token)

    /** 列表里「发送链接」的文本:密码只存了哈希,这里给不出 */
    fun sendText(request: FileRequest): String = FileRequestEditViewModel.shareText(request.title, url(request), null)

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    /** 回到这一页:静默刷新(收件计数会变,文件页里可能新建了链接,收件记录看过后「新 N」要清掉) */
    fun onResumed() {
        if (loadedOnce && loadJob?.isActive != true) load(silent = true)
    }

    fun retry() = load(silent = false)

    private fun load(silent: Boolean) {
        loadJob?.cancel()
        if (!silent) _state.update { it.copy(status = ListStatus.Loading) }
        loadJob = viewModelScope.launch {
            gopanResult { repo.myRequests() }
                .onSuccess { items ->
                    loadedOnce = true
                    _state.update { it.copy(status = ListStatus.Content, items = sortRequests(items), refreshing = false) }
                }
                .onFailure { e ->
                    val message = e.message ?: "加载失败"
                    // 手上已有列表:保留,只提示;否则显示错误页
                    val keep = _state.value.status == ListStatus.Content && _state.value.items.isNotEmpty()
                    if (keep) {
                        _state.update { it.copy(refreshing = false) }
                        _effects.trySend(FileRequestsEffect.Message(UiText.Plain(message)))
                    } else {
                        _state.update { it.copy(status = ListStatus.Error(message), refreshing = false) }
                    }
                }
        }
    }

    /** 修改保存后用服务端回显替换这一条(含最新统计),不用整表重拉 */
    fun upsert(request: FileRequest) = _state.update { s ->
        val items = if (s.items.any { it.id == request.id }) s.items.map { if (it.id == request.id) request else it } else listOf(request) + s.items
        s.copy(items = sortRequests(items))
    }

    /** 打开收件记录:那一页会标为已读,这里先把「新 N」去掉(回来时的静默刷新再以服务端为准) */
    fun openedUploads(request: FileRequest) = _state.update { s ->
        s.copy(items = s.items.map { if (it.id == request.id) it.copy(newCount = 0) else it })
    }

    fun revoke(request: FileRequest) {
        if (request.id in _state.value.revoking) return
        _state.update { it.copy(revoking = it.revoking + request.id) }
        viewModelScope.launch {
            val result = gopanResult { repo.revoke(request.id) }
            // 已经不存在(别处结束过)也当成功
            val ok = result.isSuccess || (result.exceptionOrNull() as? GopanException)?.code == GopanErrors.NOT_FOUND
            _state.update {
                it.copy(
                    revoking = it.revoking - request.id,
                    items = if (ok) it.items.filterNot { r -> r.id == request.id } else it.items,
                )
            }
            _effects.trySend(
                FileRequestsEffect.Message(
                    if (ok) UiText.Res(R.string.collect_msg_revoked) else UiText.Plain(result.exceptionOrNull()?.message ?: "操作失败"),
                ),
            )
        }
    }

    companion object {
        /** 新建的在前(服务端已按创建时间倒序,这里再保证一次) */
        fun sortRequests(items: List<FileRequest>): List<FileRequest> = items.sortedByDescending { it.createdAt }
    }
}

/**
 * 「收集」标签的内容(外层的顶栏、Snackbar 属于 SharesScreen)。点一行进收件记录,⋮ 出操作表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileRequestsTab(
    snackbar: SnackbarHostState,
    onOpenUploads: (FileRequest) -> Unit,
    viewModel: FileRequestsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val system = rememberSystemActions()
    val scope = rememberCoroutineScope()
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var revokeFor by rememberSaveable { mutableStateOf<String?>(null) }
    var editFor by rememberSaveable { mutableStateOf<String?>(null) }
    var editKey by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FileRequestsEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    fun toast(text: String) = scope.launch { snackbar.showSnackbar(text) }
    fun openUploads(request: FileRequest) {
        viewModel.openedUploads(request)
        onOpenUploads(request)
    }

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        when (val status = state.status) {
            ListStatus.Loading -> LoadingState()
            is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
            ListStatus.Content -> if (state.items.isEmpty()) {
                FullPage {
                    EmptyState(
                        title = stringResource(R.string.collect_empty),
                        description = stringResource(R.string.collect_empty_hint),
                        icon = Icons.Outlined.DriveFolderUpload,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("collect_list")) {
                    items(state.items, key = { it.id }) { request ->
                        FileRequestRow(
                            request,
                            revoking = request.id in state.revoking,
                            onClick = { openUploads(request) },
                            onMore = { sheetFor = request.id },
                        )
                    }
                }
            }
        }
    }

    val sheetRequest = state.items.firstOrNull { it.id == sheetFor }
    if (sheetRequest != null) {
        FileRequestActionSheet(
            request = sheetRequest,
            onDismiss = { sheetFor = null },
            onUploads = { openUploads(sheetRequest) },
            onCopy = { if (system.copy(viewModel.url(sheetRequest))) toast(context.getString(R.string.collect_msg_copied)) },
            onSend = {
                if (!system.sendText(viewModel.sendText(sheetRequest), context.getString(R.string.collect_send_chooser))) {
                    toast(context.getString(R.string.share_no_app))
                }
            },
            onOpen = { if (!system.openUrl(viewModel.url(sheetRequest))) toast(context.getString(R.string.shares_no_browser)) },
            onEdit = {
                editKey = System.nanoTime().toString()
                editFor = sheetRequest.id
            },
            onRevoke = { revokeFor = sheetRequest.id },
        )
    }
    // 操作表开着时那条链接没了(别处结束、刷新后消失):收起
    LaunchedEffect(sheetFor, state.items) {
        if (sheetFor != null && state.status == ListStatus.Content && state.items.none { it.id == sheetFor }) sheetFor = null
    }

    val revokeRequest = state.items.firstOrNull { it.id == revokeFor }
    if (revokeRequest != null) {
        AlertDialog(
            onDismissRequest = { revokeFor = null },
            modifier = Modifier.exposeTestTags(),
            title = { Text(stringResource(R.string.collect_revoke_title, revokeRequest.title)) },
            text = { Text(stringResource(R.string.collect_revoke_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeFor = null
                        viewModel.revoke(revokeRequest)
                    },
                    modifier = Modifier.testTag("collect_revoke_confirm"),
                ) { Text(stringResource(R.string.collect_revoke_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revokeFor = null }) { Text(stringResource(R.string.collect_revoke_keep)) } },
        )
    }

    val editRequest = state.items.firstOrNull { it.id == editFor }
    if (editRequest != null) {
        FileRequestDialog(
            target = FileRequestTarget.Edit(editRequest),
            instanceKey = editKey,
            onDismiss = { editFor = null },
            onMessage = { toast(it) },
            onSaved = viewModel::upsert,
        )
    }
}

@Composable
private fun FileRequestRow(request: FileRequest, revoking: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val expired = request.isExpired()
    ListItem(
        modifier = Modifier.clickable(enabled = !revoking, onClick = onClick).testTag("collect_row"),
        leadingContent = { Icon(Icons.Outlined.DriveFolderUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(request.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (request.hasPassword) {
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.collect_has_password), modifier = Modifier.size(16.dp))
                }
                if (request.newCount > 0) {
                    Spacer(Modifier.size(6.dp))
                    Badge(modifier = Modifier.testTag("collect_new_badge")) { Text(stringResource(R.string.collect_new_badge, request.newCount)) }
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    "${request.folderName} · ${FileRequestTexts.received(request).resolve()}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    FileRequestTexts.deadline(request).resolve(),
                    maxLines = 1,
                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onMore, enabled = !revoking, modifier = Modifier.testTag("collect_row_more")) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.preview_more))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileRequestActionSheet(
    request: FileRequest,
    onDismiss: () -> Unit,
    onUploads: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRevoke: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    val details = (listOf(UiText.Plain(request.folderName)) + FileRequestTexts.details(request)).map { it.resolve() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.exposeTestTags()) {
        ListItem(
            headlineContent = { Text(request.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(details.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider()
        SheetAction(Icons.Outlined.MoveToInbox, stringResource(R.string.collect_action_uploads), "collect_action_uploads") { pick(onUploads) }
        SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.collect_action_copy), "collect_action_copy") { pick(onCopy) }
        SheetAction(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.collect_action_send), "collect_action_send") { pick(onSend) }
        SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.collect_action_open), "collect_action_open") { pick(onOpen) }
        SheetAction(Icons.Outlined.Edit, stringResource(R.string.collect_action_edit), "collect_action_edit") { pick(onEdit) }
        SheetAction(Icons.Outlined.LinkOff, stringResource(R.string.collect_action_revoke), "collect_action_revoke", destructive = true) { pick(onRevoke) }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(label, color = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
