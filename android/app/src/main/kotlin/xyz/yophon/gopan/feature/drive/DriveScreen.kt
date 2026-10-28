package xyz.yophon.gopan.feature.drive

import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.core.ui.nav.OnTabReselect
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.OfflinePin
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
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.material.icons.outlined.NoteAdd
import xyz.yophon.gopan.core.ui.nav.LocalOpenTextEditor
import androidx.compose.material3.AlertDialog

/**
 * 文件页:根目录与任意文件夹共用(由路由决定 parentId)。
 * 顶栏 = 标题 + 面包屑(根目录不显示面包屑);多选时换成选择栏;FAB 新建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    onOpenFolder: (folderId: String) -> Unit,
    onCrumbClick: (Crumb) -> Unit,
    /** 当前在某个文件夹里时带上它(根目录为 null):搜索默认只搜这个文件夹 */
    onOpenSearch: (Crumb?) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenPreview: (nodeId: String) -> Unit,
    onBack: (() -> Unit)?,
    /** 网盘首页顶上的「收藏」「最近」入口(doc/24);true = 最近 */
    onOpenQuick: (recent: Boolean) -> Unit = {},
    /** 离线可用的文件夹没网打不开时,去看手机上的副本(doc/26) */
    onOpenOffline: (Routes.OfflineFolder) -> Unit = {},
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
    val openTextEditor = LocalOpenTextEditor.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NodeEffect.Message -> launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is NodeEffect.OpenFolder -> currentOnOpenFolder(effect.folderId)
                is NodeEffect.OpenPreview -> if (currentTwoPane) paneNodeId = effect.nodeId else currentOnOpenPreview(effect.nodeId)
                is NodeEffect.OpenTextEditor -> openTextEditor(Routes.TextEdit(nodeId = effect.nodeId, name = effect.name))
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
    // 新建按钮:往下滚收起(不挡列表末尾),往上滚或下拉时再出来
    var fabShown by rememberSaveable { mutableStateOf(true) }
    val fabOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -FAB_SCROLL_THRESHOLD) fabShown = false else if (available.y > FAB_SCROLL_THRESHOLD) fabShown = true
                return Offset.Zero
            }
        }
    }
    // 顶栏随滚动收起、往回滚就出来(面包屑不收)
    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Row(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = (if (pane != null) Modifier.weight(0.42f) else Modifier.fillMaxSize())
                .nestedScroll(fabOnScroll)
                .nestedScroll(topBarScroll.nestedScrollConnection),
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
                    DriveTopBar(state, viewModel, onBack, onCrumbClick, onOpenSearch, onOpenTrash, onOpenTransfers, topBarScroll)
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = !state.selectionMode && fabShown,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
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
                onOpenQuick = onOpenQuick,
                onOpenOffline = onOpenOffline,
                bottomPadding = padding.calculateBottomPadding(),
                modifier = (
                    if (state.isRoot && !state.selectionMode) {
                        Modifier.belowCollapsingTopBar(padding, topBarScroll)
                    } else {
                        Modifier.padding(top = padding.calculateTopPadding())
                    }
                    ).fillMaxSize(),
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
            onCreateText = viewModel::requestCreateTextFile,
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
        is DriveDialog.ReplaceUploads -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            modifier = Modifier.testTag("drive_replace_dialog"),
            title = { Text(stringResource(R.string.drive_replace_title)) },
            text = {
                Text(
                    if (d.names.size > 1) {
                        stringResource(R.string.drive_replace_text_many, d.names.first(), d.names.size)
                    } else {
                        stringResource(R.string.drive_replace_text_one, d.names.first())
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmReplaceUploads(d, replace = true) }, modifier = Modifier.testTag("drive_replace_yes")) {
                    Text(stringResource(R.string.drive_replace_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmReplaceUploads(d, replace = false) }, modifier = Modifier.testTag("drive_replace_keep")) {
                    Text(stringResource(R.string.drive_replace_keep))
                }
            },
        )
        DriveDialog.CreateTextFile -> NodeNameDialog(
            title = stringResource(R.string.textedit_new),
            label = stringResource(R.string.textedit_new_hint),
            initialValue = stringResource(R.string.textedit_new_default),
            confirmText = stringResource(R.string.drive_dialog_create),
            busy = busy,
            inputError = inputError,
            onConfirm = viewModel::createTextFile,
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
        is DriveDialog.BatchRename -> BatchRenameDialog(
            nodes = d.nodes,
            siblings = state.items.map { it.name },
            busy = busy,
            error = inputError?.takeIf { it.name == DriveViewModel.BATCH_RENAME }?.message?.resolve(),
            onApply = viewModel::applyBatchRename,
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

/** 一次滚动超过这么多像素才切换新建按钮的显隐,手指抖一下不算 */
private const val FAB_SCROLL_THRESHOLD = 6f

// ---- 顶栏 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveTopBar(
    state: DriveUiState,
    viewModel: DriveViewModel,
    onBack: (() -> Unit)?,
    onCrumbClick: (Crumb) -> Unit,
    onOpenSearch: (Crumb?) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTransfers: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val rootCrumb = Crumb(null, "")
    val title = when {
        state.isRoot -> stringResource(R.string.drive_title_root)
        else -> state.crumbs.lastOrNull()?.name?.ifEmpty { null } ?: stringResource(R.string.drive_crumb_loading)
    }
    Column {
        TopAppBar(
            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior,
            // 白底,内容滚到底下也不变深;根目录收起时淡成透明,子目录下面还有面包屑,保持白底
            colors = if (state.isRoot && scrollBehavior != null) {
                collapsingTopBarColors(scrollBehavior)
            } else {
                TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surface)
            },
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
                IconButton(onClick = { onOpenSearch(state.crumbs.lastOrNull()) }) {
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
                OverflowMenu(onOpenTrash = onOpenTrash, onOpenTransfers = onOpenTransfers, onRefresh = viewModel::refresh)
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
        windowInsets = TopBarInsets,
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
            // 选了一项是重命名,选了多项是批量重命名(doc/27)
            val many = state.selection.size > 1
            DropdownMenuItem(
                text = { Text(stringResource(if (many) R.string.drive_batch_rename else R.string.drive_action_rename)) },
                onClick = {
                    expanded = false
                    if (many) viewModel.batchRenameSelected() else viewModel.renameSelected()
                },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
            )
            val allStarred = state.selectedNodes.isNotEmpty() && state.selectedNodes.all { it.starred }
            DropdownMenuItem(
                text = { Text(stringResource(if (allStarred) R.string.drive_action_unstar else R.string.drive_action_star)) },
                onClick = {
                    expanded = false
                    viewModel.toggleStar(state.selectedNodes)
                },
                leadingIcon = { Icon(if (allStarred) Icons.Outlined.Star else Icons.Outlined.StarOutline, contentDescription = null) },
            )
            // 离线可用(doc/26):都加过了就是「取消离线可用」
            val offlinePinned = LocalOfflineMarks.current.pinned
            val allOffline = state.selectedNodes.isNotEmpty() && state.selectedNodes.all { it.id in offlinePinned }
            DropdownMenuItem(
                text = { Text(stringResource(if (allOffline) R.string.offline_action_unpin else R.string.offline_action_pin)) },
                onClick = {
                    expanded = false
                    viewModel.offlineSelected(offlinePinned)
                },
                leadingIcon = { Icon(if (allOffline) Icons.Filled.OfflinePin else Icons.Outlined.OfflinePin, contentDescription = null) },
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
private fun OverflowMenu(onOpenTrash: () -> Unit, onOpenTransfers: () -> Unit, onRefresh: () -> Unit) {
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
private fun DriveContent(
    state: DriveUiState,
    viewModel: DriveViewModel,
    onOpenQuick: (recent: Boolean) -> Unit,
    onOpenOffline: (Routes.OfflineFolder) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = modifier) {
        when (val status = state.status) {
            ListStatus.Loading -> if (state.viewMode == DriveViewMode.LIST) ListSkeleton() else GridSkeleton()
            is ListStatus.Error -> FullPage {
                // 加过离线可用的文件夹没网打不开:手机上有它的副本(doc/26)
                val folderId = viewModel.parentId
                val offline = folderId != null && folderId in LocalOfflineMarks.current.pinned
                ErrorState(
                    status.message,
                    onRetry = viewModel::retry,
                    extra = if (offline && folderId != null) {
                        { OutlinedButton(onClick = { onOpenOffline(Routes.OfflineFolder(rootId = folderId)) }) { Text(stringResource(R.string.offline_open_copy)) } }
                    } else {
                        null
                    },
                )
            }
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
                        state.viewMode == DriveViewMode.LIST -> NodeList(state, viewModel, bottomPadding, onOpenQuick)
                        else -> NodeGrid(state, viewModel, bottomPadding, onOpenQuick)
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

/** 列表模式:一行一个文件;宽屏(横屏、平板)按宽度分成两三栏,不让一行拉得老长 */
@Composable
private fun NodeList(state: DriveUiState, viewModel: DriveViewModel, bottomPadding: Dp, onOpenQuick: (Boolean) -> Unit) {
    val listState = rememberLazyGridState()
    // 换排序后新列表到达:回到顶部。否则 LazyColumn 按旧首项的 key 锚定,升降序一换直接跳到底
    LaunchedEffect(state.scrollToTopToken) { if (state.scrollToTopToken > 0) listState.scrollToItem(0) }
    // 在根目录再点底部的「文件」:滚回顶部
    OnTabReselect(Routes.DriveTab, enabled = state.isRoot) { listState.animateScrollToItem(0) }
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ListColumnMinWidth),
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + FAB_CLEARANCE),
    ) {
        if (state.isRoot && !state.selectionMode) {
            item(key = "quick", contentType = "quick", span = { GridItemSpan(maxLineSpan) }) {
                QuickEntries(onOpenQuick, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp))
            }
        }
        items(state.items, key = { it.id }, contentType = { "node" }) { node ->
            NodeRow(
                modifier = Modifier.animateItem(),
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

/** 列表末尾给新建按钮留的空 */
private val FAB_CLEARANCE = 88.dp

/** 网盘首页顶上的两个入口:收藏、最近(doc/24) */
@Composable
private fun QuickEntries(onOpenQuick: (recent: Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickEntry(Icons.Outlined.StarOutline, stringResource(R.string.quick_starred_title), "quick_entry_starred", Modifier.weight(1f)) { onOpenQuick(false) }
        QuickEntry(Icons.Outlined.History, stringResource(R.string.quick_recent_title), "quick_entry_recent", Modifier.weight(1f)) { onOpenQuick(true) }
    }
}

@Composable
private fun QuickEntry(icon: ImageVector, label: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp).testTag(tag),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun NodeGrid(state: DriveUiState, viewModel: DriveViewModel, bottomPadding: Dp, onOpenQuick: (Boolean) -> Unit) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.scrollToTopToken) { if (state.scrollToTopToken > 0) gridState.scrollToItem(0) }
    OnTabReselect(Routes.DriveTab, enabled = state.isRoot) { gridState.animateScrollToItem(0) }
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
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = bottomPadding + FAB_CLEARANCE),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.isRoot && !state.selectionMode) {
            item(key = "quick", contentType = "quick", span = { GridItemSpan(maxLineSpan) }) {
                QuickEntries(onOpenQuick)
            }
        }
        items(state.items, key = { it.id }, contentType = { "node" }) { node ->
            NodeCard(
                modifier = Modifier.animateItem(),
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
    onCreateText: () -> Unit,
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
            modifier = Modifier.clickable { pick(onCreateText) }.testTag("drive_fab_new_text"),
            leadingContent = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.textedit_new)) },
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
