package xyz.yophon.gopan.feature.chat

import xyz.yophon.gopan.feature.drive.belowCollapsingTopBar
import xyz.yophon.gopan.feature.drive.collapsingTopBarColors
import xyz.yophon.gopan.feature.drive.LocalBottomBarInset
import xyz.yophon.gopan.feature.drive.LocalBottomChrome
import xyz.yophon.gopan.feature.drive.BottomChromeState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Stable
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.MutatorMutex
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.core.ui.nav.OnTabReselect
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import xyz.yophon.gopan.core.ui.components.TabTitle
import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.chat.ChatMessage
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.transfer.rememberTransferPermissions
import xyz.yophon.gopan.core.ui.transfer.rememberUploadPickers
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets
import xyz.yophon.gopan.feature.fetch.ChatFetchBanner
import xyz.yophon.gopan.feature.fetch.FetchEffect
import xyz.yophon.gopan.feature.fetch.FetchViewModel
import xyz.yophon.gopan.feature.fetch.chatFetchOffer
import java.time.LocalDate
import java.time.ZoneId

/**
 * 传输助手:单会话消息流。列表用 reverseLayout,最新在底部;滚到顶附近往前翻历史。
 * 只在页面可见(STARTED)时轮询;系统分享进来的内容在这里落地(文本进草稿、文件直接发)。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onOpenFolder: (String) -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenPreview: (String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    fetchViewModel: FetchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 顶栏的高度交给下面的 chromeBars 按同一个进度拉,不挂它自带的滚动连接;pinned 也让顶栏本身不能单独拖
    val topBarState = remember { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior(topBarState)
    val bottomChrome = LocalBottomChrome.current
    val chromeBars = remember(bottomChrome, topBarState) { CollapsingChrome(bottomChrome, topBarState) }
    val focusManager = LocalFocusManager.current
    // 再点底部的「传输助手」:滚到最新(reverseLayout 下标 0 在底部),顶栏和输入框放出来
    OnTabReselect(Routes.ChatTab) {
        scope.launch { chromeBars.expand() }
        if (listState.layoutInfo.totalItemsCount > 0) listState.animateScrollToItem(0)
    }
    val currentOnOpenFolder by rememberUpdatedState(onOpenFolder)
    val currentOnOpenPreview by rememberUpdatedState(onOpenPreview)
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatMessage?>(null) }

    val pickers = rememberUploadPickers(
        onUris = viewModel::sendUris,
        onPhoto = { viewModel.sendFiles(listOf(it)) },
        onNoApp = { scope.launch { snackbar.showSnackbar(context.getString(R.string.transfer_msg_no_app)) } },
    )
    val permissions = rememberTransferPermissions(onStorageResult = {})

    LifecycleStartEffect(viewModel) {
        viewModel.setVisible(true)
        onStopOrDispose { viewModel.setVisible(false) }
    }
    BackHandler(enabled = state.searchOpen) { viewModel.closeSearch() }

    // 是否"跟随底部":只在一次滚动(手势或动画)结束时按当时的位置更新。不能在新数据到了之后再看位置:
    // reverseLayout 里新条目插在下标 0,LazyColumn 按 key 锚住原来的底部条目,新条目落在视口下面,那时位置已经"不在底部"
    var followBottom by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                followBottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < BOTTOM_SLOP_PX
                if (followBottom) viewModel.clearUnread()
            }
        }
    }

    val zone = remember { ZoneId.systemDefault() }
    val showingResults = state.searchOpen && state.searchResults != null
    val items = remember(state.messages, state.pending, state.uploads, state.searchResults, showingResults) {
        if (showingResults) {
            ChatLogic.buildItems(state.searchResults.orEmpty(), emptyList(), emptyList(), zone, LocalDate.now(zone))
        } else {
            ChatLogic.buildItems(state.messages, state.pending, state.uploads, zone, LocalDate.now(zone))
        }.asReversed()
    }

    // 最新一项变了(新消息、发送中的文本、上传卡片出现或换成消息):跟随底部时贴到最新
    val newestKey = items.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (followBottom && newestKey != null && !showingResults && !state.anchored) listState.scrollToItem(0)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ChatEffect.ScrollToBottom -> launch {
                    followBottom = true
                    launch { chromeBars.expand() }
                    if (listState.layoutInfo.totalItemsCount > 0) listState.animateScrollToItem(0)
                }
                is ChatEffect.ScrollToMessage -> launch {
                    val current = ChatLogic.buildItems(viewModel.state.value.messages, viewModel.state.value.pending, viewModel.state.value.uploads, zone, LocalDate.now(zone)).asReversed()
                    val index = current.indexOfFirst {
                        (it is ChatItem.Message && it.message.id == effect.id) || (it is ChatItem.MediaGroup && it.messages.any { m -> m.id == effect.id })
                    }
                    if (index >= 0) listState.scrollToItem(index)
                }
                is ChatEffect.NewMessages -> launch {
                    if (followBottom) listState.animateScrollToItem(0) else viewModel.addUnread(effect.count)
                }
                is ChatEffect.Message -> launch { snackbar.showSnackbar(effect.text) }
                is ChatEffect.Copy -> launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("gopan", effect.text)))
                    // Android 13+ 系统自己会提示已复制
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show()
                    }
                }
                is ChatEffect.OpenFolder -> currentOnOpenFolder(effect.folderId)
                is ChatEffect.OpenPreview -> currentOnOpenPreview(effect.nodeId)
                ChatEffect.TransferQueued -> permissions.onTransferQueued()
            }
        }
    }

    // 草稿里的链接交给服务器离线下载:存进传输助手文件夹,下完会话里自动出现文件卡片(轮询带回来)
    LaunchedEffect(fetchViewModel) {
        fetchViewModel.effects.collect { effect ->
            when (effect) {
                is FetchEffect.QueuedToChat -> launch { snackbar.showSnackbar(context.getString(R.string.fetch_chat_queued)) }
                is FetchEffect.Error -> launch { snackbar.showSnackbar(effect.message) }
                is FetchEffect.Queued -> Unit
            }
        }
    }

    // 顶栏与底部(输入框 + 底部导航)随滚动一起收放,共用一个进度:往旧消息里翻(手指下拉)收起,
    // 回到最新(手指上推)放出,拖动和惯性都跟着走,停下吸附到全开或全收。键盘弹起、搜索时不收
    val bottomInset = LocalBottomBarInset.current
    val imeVisible = WindowInsets.isImeVisible
    chromeBars.locked = imeVisible || state.searchOpen
    LaunchedEffect(listState, chromeBars) {
        // 新一轮滚动开始就打断吸附动画,免得和手指抢
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling -> if (!scrolling) chromeBars.settle() }
    }
    LaunchedEffect(chromeBars.locked) { if (chromeBars.locked) chromeBars.expand() }
    // 离开传输助手:底部导航放回原位
    DisposableEffect(bottomChrome) { onDispose { bottomChrome.offsetPx = 0f } }
    var inputHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 胶囊的阴影会探出上沿一点,多滑一截才藏干净
    chromeBars.rangePx = inputHeightPx + with(density) { (bottomInset + 8.dp).roundToPx() }
    // 消息列表铺到底,最后几条能滚到输入框与导航后面;底部留出它们的高度
    val bottomSpace = with(density) { inputHeightPx.toDp() } + bottomInset

    // 滚到顶附近往前翻;定位模式下滚到底附近往后续
    val nearTop by remember { derivedStateOf { val info = listState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3 } }
    LaunchedEffect(nearTop, state.messages.size) { if (nearTop && !showingResults) viewModel.loadOlder() }
    val nearBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(nearBottom, state.anchored, state.messages.size) { if (nearBottom && state.anchored) viewModel.loadNewer() }

    Scaffold(
        modifier = Modifier.nestedScroll(chromeBars.connection),
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            if (state.searchOpen) {
                SearchBar(query = query, onQuery = viewModel::setSearchQuery, onClose = viewModel::closeSearch)
            } else {
                TopAppBar(
                    windowInsets = TopBarInsets,
                    // 离线由顶栏下面的提示条说,标题不再重复一行
                    title = { TabTitle(stringResource(R.string.chat_title)) },
                    actions = {
                        IconButton(onClick = viewModel::openSearch) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.chat_search))
                        }
                        IconButton(onClick = viewModel::copyLatestText, enabled = state.latestText != null) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.chat_copy_latest))
                        }
                        IconButton(onClick = onOpenTransfers) {
                            Icon(Icons.Outlined.SwapVert, contentDescription = stringResource(R.string.chat_transfers))
                        }
                    },
                    colors = collapsingTopBarColors(topBarScroll),
                    scrollBehavior = topBarScroll,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.belowCollapsingTopBar(padding, topBarScroll).fillMaxSize().imePadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏收起时内容伸进状态栏底下:最上面那条提示条的底色跟着伸上去,字让在状态栏下面
                val underStatusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * topBarScroll.state.collapsedFraction
                val receiving = state.receiving
                receiving?.let { r ->
                    Banner(text = stringResource(R.string.chat_receiving, r.count), progress = true, topInset = underStatusBar)
                }
                if (state.offline && !state.searchOpen) {
                    Banner(text = stringResource(R.string.chat_offline), icon = true, topInset = if (receiving == null) underStatusBar else 0.dp)
                }
                // 点一下消息区(空白处或消息上都算)就退出输入、收起键盘;只旁观,不拦消息自己的点按
                Box(modifier = Modifier.weight(1f).fillMaxWidth().onTap { focusManager.clearFocus() }) {
                    when {
                        showingResults && state.searchResults.isNullOrEmpty() -> EmptyState(title = stringResource(R.string.chat_search_empty), icon = Icons.Outlined.Search)
                        state.searchOpen && state.searching && state.searchResults == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.chat_searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        !showingResults && state.loading && state.messages.isEmpty() -> LoadingState()
                        !showingResults && state.loadError != null && state.messages.isEmpty() -> ErrorState(state.loadError!!, onRetry = viewModel::retryInitial)
                        !showingResults && items.isEmpty() -> EmptyState(
                            title = stringResource(R.string.chat_empty),
                            description = stringResource(R.string.chat_empty_desc),
                            icon = Icons.Outlined.Forum,
                        )
                        else -> LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + (if (state.searchOpen) bottomInset else bottomSpace)),
                            modifier = Modifier.fillMaxSize().testTag("chat_list"),
                        ) {
                            items(items, key = { it.key }, contentType = { it::class.simpleName }) { item ->
                                ChatRow(
                                    item = item,
                                    highlightId = state.highlightId,
                                    searchMode = showingResults,
                                    viewModel = viewModel,
                                    onActions = { actionTarget = it },
                                )
                            }
                            if (!showingResults) {
                                item(key = "history-footer") {
                                    HistoryFooter(loading = state.loadingOlder, hasMore = state.hasMoreOlder, hasMessages = state.messages.isNotEmpty())
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp + bottomSpace),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // ExtendedFloatingActionButton 的文字没有进无障碍树(读屏只会念"按钮"):显式给标签
                        if (state.anchored && !state.searchOpen) {
                            val label = stringResource(R.string.chat_back_to_latest)
                            ExtendedFloatingActionButton(
                                onClick = viewModel::backToLatest,
                                icon = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                                text = { Text(label) },
                                modifier = Modifier.semantics { contentDescription = label }.testTag("chat_back_latest"),
                            )
                        } else if (state.unread > 0 && !state.searchOpen) {
                            val label = stringResource(R.string.chat_new_messages, state.unread)
                            ExtendedFloatingActionButton(
                                onClick = {
                                    viewModel.clearUnread()
                                    followBottom = true
                                    scope.launch { chromeBars.expand() }
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                                icon = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                                text = { Text(label) },
                                modifier = Modifier.semantics { contentDescription = label }.testTag("chat_unread"),
                            )
                        }
                    }
                }
            }
            // 输入框浮在消息上面(背后透明),和底部导航一起滑出屏幕
            if (!state.searchOpen) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = bottomInset)
                        .onSizeChanged { inputHeightPx = it.height }
                        .graphicsLayer { translationY = bottomChrome.offsetPx },
                ) {
                    DraftFetchBanner(viewModel, fetchViewModel)
                    InputBar(
                        viewModel = viewModel,
                        onSend = viewModel::send,
                        onPickFiles = pickers::pickFiles,
                        onPickMedia = pickers::pickMedia,
                        onCamera = pickers::takePhoto,
                    )
                }
            }
        }
    }

    actionTarget?.let { m ->
        MessageActionSheet(
            message = m,
            onDismiss = { actionTarget = null },
            onCopy = { viewModel.copy(m) },
            onOpen = { m.node?.let(viewModel::open) },
            onDownload = { m.node?.let(viewModel::download) },
            onLocateInDrive = { m.node?.let(viewModel::locateInDrive) },
            onDelete = { deleteTarget = m },
        )
    }
    deleteTarget?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = { Text(stringResource(if (m.node != null || m.isGoneFile) R.string.chat_delete_file else R.string.chat_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    viewModel.delete(m)
                }) { Text(stringResource(R.string.drive_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ChatRow(
    item: ChatItem,
    highlightId: String?,
    searchMode: Boolean,
    viewModel: ChatViewModel,
    onActions: (ChatMessage) -> Unit,
) {
    when (item) {
        is ChatItem.DayHeader -> Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                item.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ChatItem.Message -> {
            val m = item.message
            val node = m.node
            val source = ChatLogic.sourceLabel(m.source)
            Column {
                MessageShell(
                    highlighted = m.id == highlightId,
                    onClick = node?.let { n -> { viewModel.preview(n) } },
                    onLongClick = { onActions(m) },
                    testTag = "chat_msg_${m.id}",
                ) {
                    when {
                        node != null && ChatLogic.isMedia(m) -> MediaCard(node, onDownload = viewModel::download)
                        node != null || m.isGoneFile -> FileCard(m, onDownload = viewModel::download)
                        else -> TextBubble(m.body, key = m.id)
                    }
                }
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    MessageFooter(m.createdAtMillis, source, if (searchMode) ({ viewModel.locate(m) }) else null)
                }
            }
        }
        is ChatItem.MediaGroup -> {
            val first = item.messages.first()
            val last = item.messages.last()
            Column {
                MessageShell(highlighted = false, onClick = null, onLongClick = null, testTag = "chat_grid_${first.id}") {
                    MediaGrid(
                        messages = item.messages,
                        highlightId = highlightId,
                        onOpen = { m -> m.node?.let(viewModel::preview) },
                        onLongPress = onActions,
                    )
                }
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    MessageFooter(
                        last.createdAtMillis,
                        ChatLogic.sourceLabel(first.source),
                        if (searchMode) ({ viewModel.locate(first) }) else null,
                        extra = stringResource(R.string.chat_files_count, item.messages.size),
                    )
                }
            }
        }
        is ChatItem.Pending -> {
            val p = item.pending
            Column {
                MessageShell(highlighted = false, onClick = null, onLongClick = null, testTag = "chat_pending") {
                    TextBubble(p.body, key = p.id, failed = p.failed, pending = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (p.failed) {
                        Text(stringResource(R.string.chat_send_failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.retryPending(p.id) }) { Text(stringResource(R.string.chat_retry)) }
                        TextButton(onClick = { viewModel.removePending(p.id) }) { Text(stringResource(R.string.chat_remove)) }
                    } else {
                        Text(stringResource(R.string.chat_sending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        is ChatItem.Upload -> MessageShell(highlighted = false, onClick = null, onLongClick = null, testTag = "chat_upload_${item.card.task.name}") {
            UploadCardView(
                card = item.card,
                onRetry = { viewModel.retryUpload(item.card.task.id) },
                onCancel = { viewModel.cancelUpload(item.card.task.id) },
                onRemove = { viewModel.removeUpload(item.card.task.id) },
            )
        }
    }
}

@Composable
private fun HistoryFooter(loading: Boolean, hasMore: Boolean, hasMessages: Boolean) {
    if (!hasMessages) return
    Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.chat_loading_older), style = MaterialTheme.typography.labelMedium)
            }
            !hasMore -> Text(stringResource(R.string.chat_no_older), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Banner(text: String, progress: Boolean = false, icon: Boolean = false, topInset: Dp = 0.dp) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = topInset).padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (progress) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            if (icon) Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 草稿里有 http(s) 链接(多半是别的 App 分享进来的)时,输入框上方的「离线下载这个链接」。
 * 单独一个组合函数:草稿每改一个字只重组这一条,不带着整个会话列表重组。
 */
@Composable
private fun DraftFetchBanner(viewModel: ChatViewModel, fetchViewModel: FetchViewModel) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val fetch by fetchViewModel.state.collectAsStateWithLifecycle()
    val hasLinks = remember(draft) { chatFetchOffer(draft) != null }
    // 草稿里出现链接时,开关还没问到(进页面时断网)就补问一次
    LaunchedEffect(hasLinks) { if (hasLinks) fetchViewModel.checkEnabled() }
    val offer = remember(draft, fetch.enabled, fetch.chatHandled) { fetch.chatOffer(draft) } ?: return
    ChatFetchBanner(
        offer = offer,
        busy = fetch.submitting,
        onFetch = { fetchViewModel.submitToChat(draft) },
        onDismiss = { fetchViewModel.dismissChatOffer(draft) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        windowInsets = TopBarInsets,
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_search_close))
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.chat_search_hint)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("chat_search_field"),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_search_clear)) }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputBar(
    viewModel: ChatViewModel,
    onSend: () -> Unit,
    onPickFiles: () -> Unit,
    onPickMedia: () -> Unit,
    onCamera: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // 输入框自己持有文本(打字不经过 ViewModel 回写);ViewModel 改草稿时发 draftReplace 事件
    val savedDraft by viewModel.draft.collectAsStateWithLifecycle()
    val field = rememberTextFieldState(initialText = savedDraft)
    LaunchedEffect(viewModel, field) {
        launch { viewModel.draftReplace.collect { field.setTextAndPlaceCursorAtEnd(it) } }
        snapshotFlow { field.text.toString() }.collect(viewModel::setDraft)
    }
    val draft = field.text
    // 浮起的胶囊:白底、细边、投影,背后的消息照常显示;附件、输入、发送都在里面
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(4.dp)) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("chat_attach")) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(R.string.chat_attach))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_files)) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                        onClick = {
                            menu = false
                            onPickFiles()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_media)) },
                        leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                        onClick = {
                            menu = false
                            onPickMedia()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_camera)) },
                        leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                        onClick = {
                            menu = false
                            onCamera()
                        },
                    )
                }
            }
            val placeholder = stringResource(R.string.chat_input_hint)
            val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
            BasicTextField(
                state = field,
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorator = { inner ->
                    Box(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (field.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = hintColor)
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("chat_input")
                    // 粘贴 / 输入法插入的图片:直接作为文件发出去(文本照常进输入框)
                    .contentReceiver { content ->
                        if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
                        val uris = mutableListOf<android.net.Uri>()
                        val rest = content.consume { item -> item.uri?.let { uris += it; true } ?: false }
                        viewModel.sendPasted(uris)
                        rest
                    },
            )
            FilledIconButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier.testTag("chat_send"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onLocateInDrive: () -> Unit,
    onDelete: () -> Unit,
) {
    // 一次展开到底:操作多(文件页 9 项),半展开时下面几项(删除、详情)要拖上来才看得到
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    val node = message.node
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            when {
                node != null -> node.name
                message.isGoneFile -> stringResource(R.string.chat_gone_title)
                else -> stringResource(R.string.chat_action_title_message)
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        if (!message.isGoneFile) {
            SheetItem(Icons.Outlined.ContentCopy, stringResource(if (node != null) R.string.chat_action_copy_name else R.string.chat_action_copy_text)) { pick(onCopy) }
        }
        if (node != null) {
            SheetItem(Icons.Outlined.OpenInNew, stringResource(R.string.chat_action_open)) { pick(onOpen) }
            if (!node.isFolder) SheetItem(Icons.Outlined.Download, stringResource(R.string.chat_action_download)) { pick(onDownload) }
            if (!node.isFolder && node.parentId != null) SheetItem(Icons.Outlined.FolderOpen, stringResource(R.string.chat_action_locate)) { pick(onLocateInDrive) }
        }
        SheetItem(Icons.Outlined.Delete, stringResource(R.string.chat_action_delete), destructive = true) { pick(onDelete) }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(label, color = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 离底部这么近(像素)也算"在底部" */
private const val BOTTOM_SLOP_PX = 120

/**
 * 传输助手的顶栏、输入框和主界面的底部导航跟着滚动一起收放,共用一个进度,同时到位。
 * 会话倒着排(最新在底),往旧消息里翻(手指下拉)是"往列表深处走",收起;回到最新(手指上推)放出,和其它页同一个方向感。
 * 拖动和惯性都跟着走,滚动停下后吸附到全开或全收。滑出的像素写进 [BottomChromeState](MainScaffold 的底部导航按它往下移),
 * 顶栏按同样的比例收起;[locked] 时(键盘弹起、搜索中)不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
private class CollapsingChrome(private val chrome: BottomChromeState, private val topBar: TopAppBarState) {
    /** 输入框连同底部导航要滑出的距离:手指移动这么多就全收 */
    var rangePx by mutableIntStateOf(0)
    var locked by mutableStateOf(false)
    private val mutex = MutatorMutex()

    /** 底部滑出 [px](0 = 全露出,rangePx = 全收),顶栏按同样的比例收 */
    private fun moveTo(px: Float) {
        val range = rangePx.toFloat()
        chrome.offsetPx = px.coerceIn(0f, range)
        // 顶栏还没量出高度时(heightOffsetLimit 还是初始的 -MAX_VALUE)先不动它
        val limit = topBar.heightOffsetLimit
        if (range > 0f && limit > -Float.MAX_VALUE) topBar.heightOffset = limit * chrome.offsetPx / range
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (locked || rangePx == 0) return Offset.Zero
            // 手指下拉时 available.y > 0:往外滑
            moveTo(chrome.offsetPx + available.y)
            return Offset.Zero
        }
    }

    suspend fun expand() = animateTo(0f)

    suspend fun settle() = animateTo(if (chrome.offsetPx > rangePx / 2f) rangePx.toFloat() else 0f)

    private suspend fun animateTo(target: Float) = mutex.mutate {
        if (chrome.offsetPx != target) {
            animate(chrome.offsetPx, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> moveTo(value) }
        }
    }
}

/** 在这块区域里轻点一下(按下到抬起没拖动、没长按)就回调;只在 Initial 阶段旁观,不消费事件,子项的点按、长按、列表滚动照常 */
private fun Modifier.onTap(action: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
        if (up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) action()
    }
}
