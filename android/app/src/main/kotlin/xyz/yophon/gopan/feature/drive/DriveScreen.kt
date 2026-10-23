package xyz.yophon.gopan.feature.drive

import androidx.compose.foundation.shape.CircleShape
import xyz.yophon.gopan.core.ui.components.TabTitle
import xyz.yophon.gopan.core.ui.theme.LocalGopanColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import xyz.yophon.gopan.core.ui.isExpandedWidth
import xyz.yophon.gopan.feature.preview.PreviewPane
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.prefs.DriveSort
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import xyz.yophon.gopan.core.prefs.DriveViewMode
import xyz.yophon.gopan.core.ui.components.AfterDelay
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.GridSkeleton
import xyz.yophon.gopan.core.ui.components.LOADING_INDICATOR_DELAY_MS
import xyz.yophon.gopan.core.ui.components.ListSkeleton
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.transfer.rememberTransferPermissions
import xyz.yophon.gopan.core.ui.transfer.rememberUploadPickers
import xyz.yophon.gopan.feature.fetch.FetchDialog
import xyz.yophon.gopan.feature.fetch.FetchEffect
import xyz.yophon.gopan.feature.fetch.FetchViewModel
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.testTag
import java.time.Instant
import androidx.compose.material.icons.outlined.Link
import xyz.yophon.gopan.feature.links.CopyLinkViewModel

/**
 * 文件页:根目录与任意文件夹共用(由路由决定 parentId)。
 * 顶栏 = 标题 + 面包屑(根目录不显示面包屑);多选时换成选择栏;FAB 新建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    onOpenFolder: (folderId: String) -> Unit,
    onCrumbClick: (Crumb) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenPreview: (nodeId: String) -> Unit,
    onBack: (() -> Unit)?,
    onOpenRecentPlays: () -> Unit = {},
    viewModel: DriveViewModel = hiltViewModel(),
    fetchViewModel: FetchViewModel = hiltViewModel(),
    // 「复制直链」:与 NodeDialogHost 里的是同一个实例(同一个导航条目),对话框与提示由它画
    links: CopyLinkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val linkState by links.state.collectAsStateWithLifecycle()
    val fetch by fetchViewModel.state.collectAsStateWithLifecycle()
    val currentOnOpenTransfers by rememberUpdatedState(onOpenTransfers)
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val inputError by viewModel.inputError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnOpenFolder by rememberUpdatedState(onOpenFolder)
    val currentOnOpenPreview by rememberUpdatedState(onOpenPreview)
    // 平板(宽 ≥ 840dp):点文件在右侧栏预览,列表留在左边
    val twoPane = isExpandedWidth()
    val currentTwoPane by rememberUpdatedState(twoPane)
    var paneNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pickers = rememberUploadPickers(
        onUris = viewModel::uploadUris,
        onPhoto = viewModel::uploadCameraFile,
        onNoApp = { scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.transfer_msg_no_app)) } },
    )
    val permissions = rememberTransferPermissions(onStorageResult = viewModel::onStoragePermission)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NodeEffect.Message -> launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is NodeEffect.OpenFolder -> currentOnOpenFolder(effect.folderId)
                is NodeEffect.OpenPreview -> if (currentTwoPane) paneNodeId = effect.nodeId else currentOnOpenPreview(effect.nodeId)
                NodeEffect.RequestStoragePermission -> permissions.requestStoragePermission()
                NodeEffect.TransferQueued -> permissions.onTransferQueued()
            }
        }
    }
    // 离线下载:提交成功提示「已加入后台任务」,点「查看」进传输列表的后台任务栏
    LaunchedEffect(fetchViewModel) {
        fetchViewModel.effects.collect { effect ->
            when (effect) {
                is FetchEffect.Queued -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.fetch_msg_queued, effect.count),
                        actionLabel = context.getString(R.string.fetch_action_view),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        fetchViewModel.showJobs()
                        currentOnOpenTransfers()
                    }
                }
                is FetchEffect.QueuedToChat -> Unit
                is FetchEffect.Error -> launch { snackbarHostState.showSnackbar(effect.message) }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    // 侧栏开着时返回先收起侧栏
    BackHandler(enabled = !state.selectionMode && twoPane && paneNodeId != null) { paneNodeId = null }

    val pane = paneNodeId?.takeIf { twoPane }
    Row(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (pane != null) Modifier.weight(0.42f) else Modifier.fillMaxSize(),
            contentWindowInsets = NestedScreenInsets,
            topBar = {
                if (state.selectionMode) {
                    SelectionTopBar(
                        state,
                        viewModel,
                        // 多选「复制直链」:一行一条,文件夹跳过
                        onCopyLinks = if (linkState.enabled) {
                            {
                                links.copy(state.selectedNodes)
                                viewModel.clearSelection()
                            }
                        } else {
                            null
                        },
                    )
                } else {
                    DriveTopBar(state, viewModel, onBack, onCrumbClick, onOpenSearch, onOpenTrash, onOpenTransfers, onOpenRecentPlays)
                }
            },
            floatingActionButton = {
                if (!state.selectionMode) {
                    val brand = LocalGopanColors.current
                    FloatingActionButton(
                        onClick = {
                            // 离线下载开关进页面时没问到(断网)的话补问,问到了菜单里就多出「离线下载」
                            fetchViewModel.checkEnabled()
                            viewModel.openFabMenu()
                        },
                        shape = CircleShape,
                        containerColor = brand.accent,
                        contentColor = brand.onAccent,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.drive_fab_add))
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            DriveContent(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
        }
        if (pane != null) {
            VerticalDivider()
            PreviewPane(nodeId = pane, onClose = { paneNodeId = null }, modifier = Modifier.weight(0.58f).fillMaxHeight())
        }
    }

    when (val d = dialog) {
        DriveDialog.FabMenu -> FabMenuSheet(
            onCreateFolder = viewModel::requestCreateFolder,
            onUploadFiles = {
                viewModel.dismissDialog()
                pickers.pickFiles()
            },
            onUploadMedia = {
                viewModel.dismissDialog()
                pickers.pickMedia()
            },
            onCamera = {
                viewModel.dismissDialog()
                pickers.takePhoto()
            },
            onFetch = if (fetch.enabled) {
                {
                    viewModel.dismissDialog()
                    fetchViewModel.openDialog()
                }
            } else {
                null
            },
            onDismiss = viewModel::dismissDialog,
        )
        DriveDialog.CreateFolder -> NodeNameDialog(
            title = stringResource(R.string.drive_dialog_new_folder),
            label = stringResource(R.string.drive_dialog_new_folder_hint),
            initialValue = "",
            confirmText = stringResource(R.string.drive_dialog_create),
            busy = busy,
            inputError = inputError,
            onConfirm = viewModel::createFolder,
            onDismiss = viewModel::dismissDialog,
        )
        else -> NodeDialogHost(dialog = d, busy = busy, inputError = inputError, viewModel = viewModel)
    }
    if (fetch.dialogOpen) {
        FetchDialog(
            state = fetch,
            folderName = if (state.isRoot) {
                stringResource(R.string.drive_title_root)
            } else {
                state.crumbs.lastOrNull()?.name?.ifEmpty { null } ?: stringResource(R.string.drive_crumb_loading)
            },
            onTextChange = fetchViewModel::setText,
            onSubmit = { fetchViewModel.submit(viewModel.parentId) },
            onDismiss = fetchViewModel::dismissDialog,
        )
    }
}

// ---- 顶栏 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveTopBar(
    state: DriveUiState,
    viewModel: DriveViewModel,
    onBack: (() -> Unit)?,
    onCrumbClick: (Crumb) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenRecentPlays: () -> Unit,
) {
    val rootCrumb = Crumb(null, "")
    val title = when {
        state.isRoot -> stringResource(R.string.drive_title_root)
        else -> state.crumbs.lastOrNull()?.name?.ifEmpty { null } ?: stringResource(R.string.drive_crumb_loading)
    }
    Column {
        TopAppBar(
            windowInsets = NestedScreenInsets,
            title = {
                if (state.isRoot) TabTitle(title) else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                }
            },
            actions = {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.drive_action_search))
                }
                IconButton(onClick = viewModel::toggleViewMode) {
                    if (state.viewMode == DriveViewMode.LIST) {
                        Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.drive_action_view_grid))
                    } else {
                        Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = stringResource(R.string.drive_action_view_list))
                    }
                }
                SortMenu(sort = state.sort, onSelect = viewModel::selectSort)
                OverflowMenu(onOpenTrash = onOpenTrash, onOpenTransfers = onOpenTransfers, onRefresh = viewModel::refresh, onOpenRecentPlays = onOpenRecentPlays)
            },
        )
        if (!state.isRoot) {
            BreadcrumbBar(
                crumbs = listOf(rootCrumb) + state.crumbs,
                onClick = { index -> onCrumbClick(if (index == 0) rootCrumb else state.crumbs[index - 1]) },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(state: DriveUiState, viewModel: DriveViewModel, onCopyLinks: (() -> Unit)?) {
    val hasSelection = state.selection.isNotEmpty()
    TopAppBar(
        windowInsets = NestedScreenInsets,
        title = { Text(stringResource(R.string.drive_selected_count, state.selection.size)) },
        navigationIcon = {
            IconButton(onClick = viewModel::clearSelection) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.drive_exit_selection))
            }
        },
        actions = {
            IconButton(onClick = viewModel::selectAll, enabled = !state.allSelected) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.drive_select_all))
            }
            IconButton(onClick = viewModel::downloadSelected, enabled = hasSelection) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.drive_action_download))
            }
            IconButton(onClick = viewModel::moveSelected, enabled = hasSelection) {
                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.drive_action_move))
            }
            IconButton(onClick = viewModel::deleteSelected, enabled = hasSelection) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.drive_action_delete))
            }
            SelectionMoreMenu(state, viewModel, onCopyLinks)
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun SelectionMoreMenu(state: DriveUiState, viewModel: DriveViewModel, onCopyLinks: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val hasSelection = state.selection.isNotEmpty()
    Box {
        IconButton(onClick = { expanded = true }, enabled = hasSelection) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.drive_action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drive_action_rename)) },
                enabled = state.selection.size == 1,
                onClick = {
                    expanded = false
                    viewModel.renameSelected()
                },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drive_action_copy)) },
                onClick = {
                    expanded = false
                    viewModel.copySelected()
                },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drive_action_pack)) },
                onClick = {
                    expanded = false
                    viewModel.packSelected()
                },
                leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
            )
            if (onCopyLinks != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drive_action_copy_link)) },
                    enabled = state.selectedNodes.any { !it.isFolder },
                    onClick = {
                        expanded = false
                        onCopyLinks()
                    },
                    leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun SortMenu(sort: DriveSort, onSelect: (DriveSortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.drive_action_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                stringResource(R.string.drive_sort_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SORT_OPTIONS.forEach { (order, label) ->
                val active = sort.order == order
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        onSelect(order)
                    },
                    leadingIcon = {
                        if (active) {
                            Icon(
                                if (sort.desc) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

private val SORT_OPTIONS = listOf(
    DriveSortOrder.NAME to R.string.drive_sort_name,
    DriveSortOrder.SIZE to R.string.drive_sort_size,
    DriveSortOrder.UPDATED_AT to R.string.drive_sort_updated,
)

@Composable
private fun OverflowMenu(onOpenTrash: () -> Unit, onOpenTransfers: () -> Unit, onRefresh: () -> Unit, onOpenRecentPlays: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.drive_action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drive_action_refresh)) },
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drive_action_transfers)) },
                onClick = {
                    expanded = false
                    onOpenTransfers()
                },
                leadingIcon = { Icon(Icons.Outlined.SwapVert, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.media_recent_title)) },
                onClick = {
                    expanded = false
                    onOpenRecentPlays()
                },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                modifier = Modifier.testTag("drive_recent_plays"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drive_action_trash)) },
                onClick = {
                    expanded = false
                    onOpenTrash()
                },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            )
        }
    }
}

// ---- 内容 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveContent(state: DriveUiState, viewModel: DriveViewModel, modifier: Modifier = Modifier) {
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = modifier) {
        when (val status = state.status) {
            ListStatus.Loading -> if (state.viewMode == DriveViewMode.LIST) ListSkeleton() else GridSkeleton()
            is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
            ListStatus.Content -> Column(modifier = Modifier.fillMaxSize()) {
                // 先显示缓存、联网还没回来时不算离线:不出提示条,只在顶上压一条细进度
                if (state.fromCache && !state.revalidating) OfflineBanner(fetchedAt = state.fetchedAt, onRetry = viewModel::refresh)
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        state.items.isEmpty() -> FullPage {
                            EmptyState(
                                title = stringResource(R.string.drive_empty_title),
                                description = stringResource(R.string.drive_empty_desc),
                                icon = Icons.Outlined.FolderOpen,
                            )
                        }
                        state.viewMode == DriveViewMode.LIST -> NodeList(state, viewModel)
                        else -> NodeGrid(state, viewModel)
                    }
                    if (state.revalidating) {
                        AfterDelay(LOADING_INDICATOR_DELAY_MS) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("drive_revalidating"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner(fetchedAt: Long, onRetry: () -> Unit) {
    val ago = formatRelativeTime(Instant.ofEpochMilli(fetchedAt).toString())
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.drive_offline_banner, ago),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun NodeList(state: DriveUiState, viewModel: DriveViewModel) {
    val listState = rememberLazyListState()
    // 换排序后新列表到达:回到顶部。否则 LazyColumn 按旧首项的 key 锚定,升降序一换直接跳到底
    LaunchedEffect(state.scrollToTopToken) { if (state.scrollToTopToken > 0) listState.scrollToItem(0) }
    StickToTop(
        firstKey = state.items.firstOrNull()?.id,
        isScrolling = { listState.isScrollInProgress },
        atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 },
        scrollToTop = { listState.scrollToItem(0) },
    )
    LoadMoreTrigger(
        itemCount = state.items.size,
        isNearEnd = { listState.isNearEnd() },
        onLoadMore = viewModel::loadMore,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(state.items, key = { it.id }, contentType = { "node" }) { node ->
            NodeRow(
                node = node,
                secondary = nodeSecondaryText(node),
                selectionMode = state.selectionMode,
                selected = node.id in state.selection,
                onClick = { viewModel.onItemClick(node) },
                onLongClick = { viewModel.onItemLongClick(node) },
                onMore = { viewModel.showActions(node) },
                onThumbError = viewModel::onThumbError,
            )
        }
        if (state.nextCursor != null || state.isLoadingMore || state.loadMoreError != null) {
            item(key = "footer", contentType = "footer") {
                LoadMoreFooter(isLoading = state.isLoadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore)
            }
        }
    }
}

@Composable
private fun NodeGrid(state: DriveUiState, viewModel: DriveViewModel) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.scrollToTopToken) { if (state.scrollToTopToken > 0) gridState.scrollToItem(0) }
    StickToTop(
        firstKey = state.items.firstOrNull()?.id,
        isScrolling = { gridState.isScrollInProgress },
        atTop = { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 },
        scrollToTop = { gridState.scrollToItem(0) },
    )
    LoadMoreTrigger(
        itemCount = state.items.size,
        isNearEnd = { gridState.isNearEnd() },
        onLoadMore = viewModel::loadMore,
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.items, key = { it.id }, contentType = { "node" }) { node ->
            NodeCard(
                node = node,
                secondary = nodeSecondaryText(node),
                selectionMode = state.selectionMode,
                selected = node.id in state.selection,
                onClick = { viewModel.onItemClick(node) },
                onLongClick = { viewModel.onItemLongClick(node) },
                onMore = { viewModel.showActions(node) },
                onThumbError = viewModel::onThumbError,
            )
        }
        if (state.nextCursor != null || state.isLoadingMore || state.loadMoreError != null) {
            item(key = "footer", contentType = "footer", span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreFooter(isLoading = state.isLoadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore)
            }
        }
    }
}

/**
 * 停在顶部时,列表顶上新增的条目(下拉刷新、上传完成后静默刷新)要露出来:LazyColumn / LazyGrid 按 key 锚定原来的首项,
 * 插在它前面的新条目会落在视口上方。"停在顶部"只在一次滚动结束时按当时的位置更新,数据变了之后再看位置就晚了。
 */
@Composable
private fun StickToTop(firstKey: String?, isScrolling: () -> Boolean, atTop: () -> Boolean, scrollToTop: suspend () -> Unit) {
    var stick by remember { mutableStateOf(true) }
    val currentIsScrolling by rememberUpdatedState(isScrolling)
    val currentAtTop by rememberUpdatedState(atTop)
    LaunchedEffect(Unit) {
        snapshotFlow { currentIsScrolling() }.collect { scrolling -> if (!scrolling) stick = currentAtTop() }
    }
    LaunchedEffect(firstKey) { if (stick && firstKey != null) scrollToTop() }
}

// ---- FAB 菜单 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FabMenuSheet(
    onCreateFolder: () -> Unit,
    onUploadFiles: () -> Unit,
    onUploadMedia: () -> Unit,
    onCamera: () -> Unit,
    /** 离线下载;本站没开时为 null(不显示) */
    onFetch: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ListItem(
            modifier = Modifier.clickable { pick(onCreateFolder) },
            leadingContent = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.drive_fab_new_folder)) },
        )
        ListItem(
            modifier = Modifier.clickable { pick(onUploadFiles) },
            leadingContent = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.drive_fab_upload)) },
        )
        ListItem(
            modifier = Modifier.clickable { pick(onUploadMedia) },
            leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.drive_fab_upload_media)) },
        )
        ListItem(
            modifier = Modifier.clickable { pick(onCamera) },
            leadingContent = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.drive_fab_camera)) },
        )
        if (onFetch != null) {
            ListItem(
                modifier = Modifier.clickable { pick(onFetch) }.testTag("drive_fab_fetch"),
                leadingContent = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.fetch_fab)) },
            )
        }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}
