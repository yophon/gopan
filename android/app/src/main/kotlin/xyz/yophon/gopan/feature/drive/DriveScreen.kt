package xyz.yophon.gopan.feature.drive

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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import java.time.Instant

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
    onBack: (() -> Unit)?,
    viewModel: DriveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val inputError by viewModel.inputError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnOpenFolder by rememberUpdatedState(onOpenFolder)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NodeEffect.Message -> launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is NodeEffect.OpenFolder -> currentOnOpenFolder(effect.folderId)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(state, viewModel)
            } else {
                DriveTopBar(state, viewModel, onBack, onCrumbClick, onOpenSearch, onOpenTrash)
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButton(onClick = viewModel::openFabMenu) {
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

    when (val d = dialog) {
        DriveDialog.FabMenu -> FabMenuSheet(
            onCreateFolder = viewModel::requestCreateFolder,
            onUpload = {
                viewModel.dismissDialog()
                viewModel.notYet()
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
) {
    val rootCrumb = Crumb(null, "")
    val title = when {
        state.isRoot -> stringResource(R.string.drive_title_root)
        else -> state.crumbs.lastOrNull()?.name?.ifEmpty { null } ?: stringResource(R.string.drive_crumb_loading)
    }
    Column {
        TopAppBar(
            windowInsets = NestedScreenInsets,
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                OverflowMenu(onOpenTrash = onOpenTrash, onRefresh = viewModel::refresh)
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
private fun SelectionTopBar(state: DriveUiState, viewModel: DriveViewModel) {
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
            IconButton(onClick = viewModel::moveSelected, enabled = hasSelection) {
                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.drive_action_move))
            }
            IconButton(onClick = viewModel::copySelected, enabled = hasSelection) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.drive_action_copy))
            }
            IconButton(onClick = viewModel::deleteSelected, enabled = hasSelection) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.drive_action_delete))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
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
private fun OverflowMenu(onOpenTrash: () -> Unit, onRefresh: () -> Unit) {
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
            ListStatus.Loading -> LoadingState()
            is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
            ListStatus.Content -> Column(modifier = Modifier.fillMaxSize()) {
                if (state.fromCache) OfflineBanner(fetchedAt = state.fetchedAt, onRetry = viewModel::refresh)
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
            )
        }
        if (state.nextCursor != null || state.isLoadingMore || state.loadMoreError != null) {
            item(key = "footer", contentType = "footer", span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreFooter(isLoading = state.isLoadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore)
            }
        }
    }
}

// ---- FAB 菜单 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FabMenuSheet(onCreateFolder: () -> Unit, onUpload: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
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
            modifier = Modifier.clickable { pick(onUpload) },
            leadingContent = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.drive_fab_upload)) },
            supportingContent = { Text(stringResource(R.string.drive_fab_upload_hint)) },
        )
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}
